package io.github.iprodigy

import com.github.twitch4j.auth.providers.TwitchIdentityProvider
import com.github.twitch4j.common.util.ThreadUtils
import com.github.twitch4j.eventsub.EventSubSubscriptionStatus
import com.github.twitch4j.eventsub.domain.SuspiciousStatus
import com.github.twitch4j.eventsub.domain.chat.Message
import com.github.twitch4j.eventsub.domain.moderation.Action
import com.github.twitch4j.eventsub.domain.moderation.UserTarget
import com.github.twitch4j.eventsub.events.*
import com.github.twitch4j.eventsub.socket.conduit.TwitchConduitSocketPool
import com.github.twitch4j.eventsub.socket.events.ConduitShardReassociationFailureEvent
import com.github.twitch4j.eventsub.subscriptions.SubscriptionTypes
import com.github.twitch4j.helix.TwitchHelixBuilder
import com.github.twitch4j.helix.domain.ChatMessage
import io.github.iprodigy.util.ConcurrentBoundedDeque
import io.github.iprodigy.util.executeOrNull
import io.github.xanthic.cache.api.Cache
import io.github.xanthic.cache.api.RemovalListener
import io.github.xanthic.cache.api.domain.ExpiryType
import io.github.xanthic.cache.ktx.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

const val MAX_CHANNELS = 65_536L
const val MAX_CHATTERS_PER_CHANNEL = 16_384L
const val MESSAGES_PER_USER = 5

const val NOT_SHARING = ""

val MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

val httpClient = OkHttpClient()

val exec = ThreadUtils.getDefaultScheduledThreadPoolExecutor(
    "shared-mod-helper",
    Runtime.getRuntime().availableProcessors().coerceAtMost(10)
)!!

val identityProvider = TwitchIdentityProvider(CLIENT_ID, CLIENT_SECRET, null)

val token = identityProvider.getAppAccessToken()!!

val helix = TwitchHelixBuilder.builder()
    .withClientId(CLIENT_ID)
    .withClientSecret(CLIENT_SECRET)
    .withScheduledThreadPoolExecutor(exec)
    .withDefaultAuthToken(token)
    .build()!!

val messages: MutableMap<String, Cache<String, Queue<CachedMessage>>> = ConcurrentHashMap()

val sharingChannels = createCache<String, String> {
    maxSize = MAX_CHANNELS
    expiryType = ExpiryType.POST_WRITE
    expiryTime = Duration.ofHours(48L)
    removalListener = RemovalListener { key, _, _ ->
        messages.remove(key)
    }
}

fun main() {
    // keep app access token healthy
    exec.scheduleWithFixedDelay({
        try {
            token.updateCredential(identityProvider.getAppAccessToken())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }, 14L, 7L, TimeUnit.DAYS)

    // prepare conduit
    val oldConduit = helix.getConduits(null).execute().conduits.firstOrNull()
    val conduit = TwitchConduitSocketPool.create {
        it.clientId(CLIENT_ID)
        it.clientSecret(CLIENT_SECRET)
        it.helix(helix)
        it.executor(exec)
        it.conduitId(oldConduit?.id)
        it.poolShards(oldConduit?.shardCount ?: 4)
    }

    if (oldConduit == null) {
        conduit.register(SubscriptionTypes.USER_AUTHORIZATION_GRANT) { it.clientId(CLIENT_ID).build() }
        conduit.register(SubscriptionTypes.USER_AUTHORIZATION_REVOKE) { it.clientId(CLIENT_ID).build() }
    }

    conduit.eventManager.onEvent(ConduitShardReassociationFailureEvent::class.java) {
        println(it)
    }

    conduit.eventManager.onEvent(UserAuthorizationGrantEvent::class.java) { e ->
        exec.execute {
            val fresh = helix.getEventSubSubscriptions(null, null, null, e.userId, null, null)
                .executeOrNull()
                ?.subscriptions
                ?.isEmpty()
                ?: true
            if (fresh) {
                conduit.register(SubscriptionTypes.CHANNEL_MODERATE) {
                    it.broadcasterUserId(e.userId).moderatorUserId(e.userId).build()
                }
                conduit.register(SubscriptionTypes.CHANNEL_CHAT_MESSAGE) {
                    it.broadcasterUserId(e.userId).userId(e.userId).build()
                }
                conduit.register(SubscriptionTypes.CHANNEL_SHARED_CHAT_BEGIN) { it.broadcasterUserId(e.userId).build() }
                conduit.register(SubscriptionTypes.CHANNEL_SHARED_CHAT_UPDATE) {
                    it.broadcasterUserId(e.userId).build()
                }
                conduit.register(SubscriptionTypes.CHANNEL_SHARED_CHAT_END) { it.broadcasterUserId(e.userId).build() }
                conduit.register(SubscriptionTypes.CHANNEL_CHAT_NOTIFICATION) {
                    it.broadcasterUserId(e.userId).userId(e.userId).build()
                }
                conduit.register(SubscriptionTypes.CHANNEL_SUSPICIOUS_USER_MESSAGE) {
                    it.broadcasterUserId(e.userId).moderatorUserId(e.userId).build()
                }
            }

            loadSharing(e.userId)
        }
    }

    conduit.eventManager.onEvent(UserAuthorizationRevokeEvent::class.java) {
        sharingChannels.remove(it.userId)
        exec.execute {
            helix.getEventSubSubscriptions(null, null, null, it.userId, null, null)
                .execute()
                .subscriptions
                .filter { sub -> sub.status == EventSubSubscriptionStatus.ENABLED }
                .forEach { sub -> helix.deleteEventSubSubscription(null, sub.id).executeOrNull() }
        }
    }

    conduit.eventManager.onEvent(ChannelModerateEvent::class.java) { e ->
        val user: UserTarget
        val duration: Int
        val reason: String?
        when (e.action) {
            Action.SHARED_CHAT_BAN -> {
                user = e.sharedChatBan!!
                duration = -1
                reason = e.sharedChatBan!!.reason
            }

            Action.SHARED_CHAT_TIMEOUT -> {
                user = e.sharedChatTimeout!!
                duration = Duration.between(Instant.now(), e.sharedChatTimeout!!.expiresAt)
                    .seconds
                    .toInt()
                    .coerceAtLeast(1)
                reason = e.sharedChatTimeout!!.reason
            }

            Action.SHARED_CHAT_UNBAN -> {
                user = e.sharedChatUnban!!
                duration = 0
                reason = null
            }

            Action.SHARED_CHAT_UNTIMEOUT -> {
                user = e.sharedChatUntimeout!!
                duration = 0
                reason = null
            }

            else -> return@onEvent
        }

        val queue = messages[e.broadcasterUserId]?.remove(user.userId)
        val payload = Payload(
            channelId = e.broadcasterUserId,
            channelLogin = e.broadcasterUserLogin,
            channelName = e.broadcasterUserName,
            userId = user.userId,
            userLogin = user.userLogin,
            userName = user.userName,
            sourceRoomId = e.sourceBroadcasterUserId ?: "",
            sourceRoomLogin = e.sourceBroadcasterUserLogin ?: "",
            sourceRoomName = e.sourceBroadcasterUserName ?: "",
            moderatorId = e.moderatorUserId,
            moderatorLogin = e.moderatorUserLogin,
            moderatorName = e.moderatorUserName,
            timestamp = Instant.now().epochSecond,
            duration = duration,
            reason = reason,
            messages = queue
        )

        val body = Json.encodeToString(payload).toRequestBody(MEDIA_TYPE)
        val req = Request.Builder()
            .url(BACKEND_URL)
            .header("Authorization", "Bearer $DB_TOKEN")
            .post(body)
            .build()
        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        println(response.body?.toString())
                    }
                }
            }
        })
    }

    fun handleChatEvent(
        broadcasterUserId: String,
        chatterUserId: String,
        message: Message?,
        sourceRoomId: String?,
        sourceRoomLogin: String?
    ) {
        val msg = message?.cleanedText?.takeIf { it.isNotEmpty() } ?: return
        val cachedSession = sharingChannels[broadcasterUserId]
        if (cachedSession == NOT_SHARING) return

        if (cachedSession == null) {
            if (sourceRoomId != null && sourceRoomId != broadcasterUserId) {
                cacheMessage(broadcasterUserId, chatterUserId, msg, sourceRoomId, sourceRoomLogin)
                exec.execute { loadSharing(broadcasterUserId) }
            } else {
                exec.execute {
                    synchronized(sharingChannels) {
                        if (sharingChannels[broadcasterUserId] == null) {
                            loadSharing(broadcasterUserId)
                        }
                    }

                    if (!sharingChannels[broadcasterUserId].isNullOrEmpty()) {
                        cacheMessage(broadcasterUserId, chatterUserId, msg, sourceRoomId, sourceRoomLogin)
                    }
                }
            }
        } else {
            cacheMessage(broadcasterUserId, chatterUserId, msg, sourceRoomId, sourceRoomLogin)
        }
    }

    conduit.eventManager.onEvent(ChannelChatMessageEvent::class.java) {
        handleChatEvent(
            it.broadcasterUserId,
            it.chatterUserId,
            it.message,
            it.sourceBroadcasterUserId,
            it.sourceBroadcasterUserLogin
        )
    }

    conduit.eventManager.onEvent(ChannelChatNotificationEvent::class.java) {
        handleChatEvent(
            it.broadcasterUserId,
            it.chatterUserId,
            it.message,
            it.sourceBroadcasterUserId,
            it.sourceBroadcasterUserLogin
        )
    }

    conduit.eventManager.onEvent(SuspiciousUserMessageEvent::class.java) {
        if (it.status == SuspiciousStatus.RESTRICTED) {
            handleChatEvent(it.broadcasterUserId, it.userId, it.message, null, null)
        }
    }

    conduit.eventManager.onEvent(ChannelSharedChatBeginEvent::class.java) {
        sharingChannels.put(it.broadcasterUserId, it.sessionId)
    }

    conduit.eventManager.onEvent(ChannelSharedChatUpdateEvent::class.java) {
        sharingChannels.put(it.broadcasterUserId, it.sessionId)
    }

    conduit.eventManager.onEvent(ChannelSharedChatEndEvent::class.java) {
        sharingChannels.remove(it.broadcasterUserId)

        val message = "The shared chat session has ended! Mods, you can review moderation actions at our website"
        helix.sendChatMessage(null, ChatMessage(it.broadcasterUserId, BOT_ID, message, null)).executeOrNull()
    }
}

private fun loadSharing(channelId: String) {
    val session = helix.getSharedChatSession(null, channelId).execute().get()
    sharingChannels.put(channelId, session?.sessionId ?: NOT_SHARING)
}

private fun cacheMessage(
    channelId: String,
    userId: String,
    message: String,
    sourceRoomId: String?,
    sourceRoomLogin: String?
) {
    val cache = messages.computeIfAbsent(channelId) {
        createCache {
            maxSize = MAX_CHATTERS_PER_CHANNEL
            expiryType = ExpiryType.POST_ACCESS
            expiryTime = Duration.ofMinutes(30L)
        }
    }

    val queue = cache.computeIfAbsent(userId) {
        ConcurrentBoundedDeque(MESSAGES_PER_USER)
    }

    queue.offer(CachedMessage(message, Instant.now().epochSecond, sourceRoomId, sourceRoomLogin))
}

@Serializable
data class CachedMessage(
    val text: String,
    val ts: Long? = null,
    val sourceId: String? = null,
    val sourceLogin: String? = null
)

@Serializable
data class Payload(
    val channelId: String,
    val channelLogin: String,
    val channelName: String,
    val userId: String,
    val userLogin: String,
    val userName: String,
    val sourceRoomId: String,
    val sourceRoomLogin: String,
    val sourceRoomName: String,
    val moderatorId: String,
    val moderatorLogin: String,
    val moderatorName: String,
    val timestamp: Long,
    val duration: Int,
    val reason: String?,
    val messages: Collection<CachedMessage>?
)

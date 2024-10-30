package io.github.iprodigy

import com.github.twitch4j.auth.providers.TwitchIdentityProvider
import com.github.twitch4j.client.websocket.domain.WebsocketConnectionState
import com.github.twitch4j.common.util.ThreadUtils
import com.github.twitch4j.eventsub.EventSubSubscriptionStatus
import com.github.twitch4j.eventsub.domain.SuspiciousStatus
import com.github.twitch4j.eventsub.domain.chat.Fragment
import com.github.twitch4j.eventsub.domain.chat.Message
import com.github.twitch4j.eventsub.domain.moderation.Action
import com.github.twitch4j.eventsub.domain.moderation.UserTarget
import com.github.twitch4j.eventsub.events.*
import com.github.twitch4j.eventsub.socket.conduit.TwitchConduitSocketPool
import com.github.twitch4j.eventsub.socket.events.ConduitShardReassociationFailureEvent
import com.github.twitch4j.eventsub.socket.events.EventSocketClosedByTwitchEvent
import com.github.twitch4j.eventsub.socket.events.EventSocketConnectionStateEvent
import com.github.twitch4j.eventsub.socket.events.EventSocketWelcomedEvent
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
import org.slf4j.LoggerFactory
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

val log = LoggerFactory.getLogger("io.github.iprodigy.MainKt")!!

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
    log.info("Starting application...")

    // keep app access token healthy
    exec.scheduleWithFixedDelay({
        try {
            token.updateCredential(identityProvider.getAppAccessToken())
        } catch (e: Exception) {
            log.warn("Failed to regenerate credential", e)
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
        log.debug("Created new conduit with ID: {}", conduit.conduitId)
        conduit.register(SubscriptionTypes.USER_AUTHORIZATION_GRANT) { it.clientId(CLIENT_ID).build() }
        conduit.register(SubscriptionTypes.USER_AUTHORIZATION_REVOKE) { it.clientId(CLIENT_ID).build() }
    } else {
        log.debug("Reusing existing conduit with ID: {}", oldConduit.id)
    }

    conduit.eventManager.onEvent(EventSocketWelcomedEvent::class.java) {
        if (it.isSessionChanged) {
            log.info("Shard freshly connected: {}", it.sessionId)
        } else {
            log.debug("Shared gracefully reconnected: {}", it.sessionId)
        }
    }

    conduit.eventManager.onEvent(EventSocketClosedByTwitchEvent::class.java) {
        log.warn("Twitch closed shard {} due to {}", it.connection.websocketId, it.reason)
    }

    conduit.eventManager.onEvent(EventSocketConnectionStateEvent::class.java) {
        if (it.state == WebsocketConnectionState.LOST) {
            log.warn("Shard lost connection")
        }
    }

    conduit.eventManager.onEvent(ConduitShardReassociationFailureEvent::class.java) {
        log.error("Failed to re-associate shard {} with conduit", it.shardId, it.exception)
    }

    conduit.eventManager.onEvent(UserAuthorizationGrantEvent::class.java) { e ->
        exec.execute {
            val fresh = helix.getEventSubSubscriptions(null, null, null, e.userId, null, null)
                .executeOrNull { log.warn("Failed to obtain eventsub subscriptions for {}", e.userId, it) }
                ?.subscriptions
                ?.isEmpty()
                ?: true
            if (fresh) {
                log.debug("Received fresh authorization: {}", e)
                conduit.register(SubscriptionTypes.CHANNEL_MODERATE) {
                    it.broadcasterUserId(e.userId).moderatorUserId(e.userId).build()
                }
                conduit.register(SubscriptionTypes.CHANNEL_CHAT_MESSAGE) {
                    it.broadcasterUserId(e.userId).userId(e.userId).build()
                }
                conduit.register(SubscriptionTypes.CHANNEL_SHARED_CHAT_BEGIN) {
                    it.broadcasterUserId(e.userId).build()
                }
                conduit.register(SubscriptionTypes.CHANNEL_SHARED_CHAT_UPDATE) {
                    it.broadcasterUserId(e.userId).build()
                }
                conduit.register(SubscriptionTypes.CHANNEL_SHARED_CHAT_END) {
                    it.broadcasterUserId(e.userId).build()
                }
                conduit.register(SubscriptionTypes.CHANNEL_CHAT_NOTIFICATION) {
                    it.broadcasterUserId(e.userId).userId(e.userId).build()
                }
                conduit.register(SubscriptionTypes.CHANNEL_SUSPICIOUS_USER_MESSAGE) {
                    it.broadcasterUserId(e.userId).moderatorUserId(e.userId).build()
                }
            } else {
                log.debug("Received repeat authorization: {}", e)
            }

            storeAuth(e.userId, e.userLogin, e.userName, true)
            loadSharing(e.userId)
        }
    }

    conduit.eventManager.onEvent(UserAuthorizationRevokeEvent::class.java) {
        sharingChannels.remove(it.userId)
        exec.execute {
            storeAuth(it.userId, it.userLogin, it.userName, false)
            helix.getEventSubSubscriptions(null, null, null, it.userId, null, null)
                .execute()
                .subscriptions
                .filter { sub -> sub.status == EventSubSubscriptionStatus.ENABLED }
                .forEach { sub ->
                    helix.deleteEventSubSubscription(null, sub.id).executeOrNull { e ->
                        log.warn("Failed to delete eventsub subscription with ID {} for {}", sub.id, it.userId, e)
                    }
                }
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
                log.warn("Failed to post moderation action: {}", payload, e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        log.warn(
                            "Unsuccessfully posted moderation action: {} due to {}",
                            payload,
                            response.body?.toString()
                        )
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
                cacheMessage(broadcasterUserId, chatterUserId, msg, message, sourceRoomId, sourceRoomLogin)
                exec.execute { loadSharing(broadcasterUserId) }
            } else {
                exec.execute {
                    synchronized(sharingChannels) {
                        if (sharingChannels[broadcasterUserId] == null) {
                            loadSharing(broadcasterUserId)
                        }
                    }

                    if (!sharingChannels[broadcasterUserId].isNullOrEmpty()) {
                        cacheMessage(broadcasterUserId, chatterUserId, msg, message, sourceRoomId, sourceRoomLogin)
                    }
                }
            }
        } else {
            cacheMessage(broadcasterUserId, chatterUserId, msg, message, sourceRoomId, sourceRoomLogin)
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
        helix.sendChatMessage(null, ChatMessage(it.broadcasterUserId, BOT_ID, message, null))
            .executeOrNull { e -> log.warn("Failed to send chat message to {}", it.broadcasterUserLogin, e) }
            ?.get()
            ?.dropReason
            ?.run { log.debug("Could not message {} due to {}", it.broadcasterUserLogin, this) }
    }

    log.info("Completed application initialization.")
}

private fun loadSharing(channelId: String) {
    val session = helix.getSharedChatSession(null, channelId)
        .executeOrNull { log.warn("Could not query shared session for {}", channelId, it) }
        ?: return
    sharingChannels.put(channelId, session.get()?.sessionId ?: NOT_SHARING)
}

private fun storeAuth(channelId: String, channelLogin: String, channelName: String, added: Boolean) {
    val name = if (channelLogin.equals(channelName, ignoreCase = true)) channelName else channelLogin
    val image = added.takeIf { it }
        ?.let {
            helix.getUsers(null, listOf(channelId), null)
                .executeOrNull { log.warn("Could not query user {}", channelId, it) }
                ?.users
                ?.firstOrNull()
                ?.profileImageUrl
        } ?: ""
    val payload = AuthPayload(channelId, name, image, Instant.now().epochSecond, added)
    val body = Json.encodeToString(payload).toRequestBody(MEDIA_TYPE)
    val req = Request.Builder()
        .url(BACKEND_URL)
        .header("Authorization", "Bearer $DB_TOKEN")
        .put(body)
        .build()
    httpClient.newCall(req).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            log.warn("Could not store authorization: {}", payload, e)
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                if (!response.isSuccessful) {
                    log.warn("Unsuccessfully put authorization: {} due to {}", payload, response.body?.toString())
                }
            }
        }
    })
}

private fun cacheMessage(
    channelId: String,
    userId: String,
    msg: String,
    message: Message,
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

    queue.offer(
        CachedMessage(
            text = msg,
            ts = Instant.now().epochSecond,
            sourceId = sourceRoomId,
            sourceLogin = sourceRoomLogin,
            fragments = parseFragments(message),
            emotes = parseEmotes(message)
        )
    )
}

fun parseEmotes(message: Message): Map<String, String> {
    if (message.fragments.isNullOrEmpty()) return emptyMap()
    return message.fragments.filter { it.type == Fragment.Type.EMOTE }.associate { it.text to it.emote!!.id }
}

fun parseFragments(message: Message): List<SimpleFragment> {
    if (message.fragments.isNullOrEmpty())
        return listOf(SimpleFragment(message.cleanedText))

    return message.fragments.mapNotNull {
        when (it.type) {
            Fragment.Type.CHEERMOTE -> {
                val cheermote = it.cheermote!!
                SimpleFragment(cheermote.prefix + cheermote.bits)
            }

            Fragment.Type.EMOTE -> {
                val emote = it.emote!!
                SimpleFragment(it.text, emote.id)
            }

            Fragment.Type.MENTION -> {
                val name = it.mention!!.userLogin.equals(it.mention!!.userName, ignoreCase = true)
                SimpleFragment("@$name")
            }

            else -> SimpleFragment(it.text)
        }
    }
}

@Serializable
data class SimpleFragment(val text: String, val emoteId: String? = null)

@Serializable
data class CachedMessage(
    val text: String,
    val ts: Long? = null,
    val sourceId: String? = null,
    val sourceLogin: String? = null,
    val fragments: List<SimpleFragment>? = null,
    val emotes: Map<String, String>? = null
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

@Serializable
data class AuthPayload(
    val channelId: String,
    val channelName: String,
    val imageUrl: String,
    val timestamp: Long,
    val added: Boolean
)

package com.mewna.event;

import com.mewna.Mewna;
import com.mewna.catnip.entity.Entity;
import com.mewna.catnip.entity.impl.GuildImpl;
import com.mewna.catnip.entity.impl.MemberImpl;
import com.mewna.catnip.entity.impl.MessageImpl;
import com.mewna.catnip.entity.impl.UserImpl;
import com.mewna.catnip.shard.DiscordEvent.Raw;
import com.mewna.event.discord.DiscordGuildMemberAdd;
import com.mewna.event.discord.DiscordGuildMemberRemove;
import com.mewna.event.discord.DiscordMessageCreate;
import com.mewna.plugin.event.EventType;
import com.mewna.plugin.event.audio.NekoTrackEvent;
import com.mewna.plugin.event.plugin.twitch.TwitchStreamData;
import com.mewna.plugin.event.plugin.twitch.TwitchStreamEndEvent;
import com.mewna.plugin.event.plugin.twitch.TwitchStreamStartEvent;
import com.mewna.plugin.event.plugin.twitch.TwitchStreamer;
import gg.amy.singyeong.Dispatch;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author amy
 * @since 10/27/18.
 */
public class SingyeongEventManager {
    private final Mewna mewna;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    
    public SingyeongEventManager(final Mewna mewna) {
        this.mewna = mewna;
    }
    
    public void handle(final Dispatch dispatch) {
        final JsonObject data = dispatch.data();
        if(data.containsKey("type") && dispatch.nonce() == null) {
            final String type = data.getString("type");
            switch(type) {
                case Raw.MESSAGE_CREATE: {
                    final var event = DiscordMessageCreate.builder()
                            .guild(Entity.fromJson(mewna.catnip(), GuildImpl.class, data.getJsonObject("guild")))
                            .member(Entity.fromJson(mewna.catnip(), MemberImpl.class, data.getJsonObject("member")))
                            .user(Entity.fromJson(mewna.catnip(), UserImpl.class, data.getJsonObject("user")))
                            .message(Entity.fromJson(mewna.catnip(), MessageImpl.class, data.getJsonObject("message")))
                            .build();
                    if(System.getenv("DEBUG") != null && event.user().id().equals("128316294742147072")) {
                        logger.info("[COMMAND DEBUG] Got message from you: {}", event.message().content());
                    }
                    mewna.commandManager().tryExecCommand(event);
                    break;
                }
                case Raw.GUILD_MEMBER_ADD: {
                    final var event = DiscordGuildMemberAdd.builder()
                            .guild(Entity.fromJson(mewna.catnip(), GuildImpl.class, data.getJsonObject("guild")))
                            .member(Entity.fromJson(mewna.catnip(), MemberImpl.class, data.getJsonObject("member")))
                            .user(Entity.fromJson(mewna.catnip(), UserImpl.class, data.getJsonObject("user")))
                            .build();
                    mewna.pluginManager().processEvent(type, event);
                    break;
                }
                case Raw.GUILD_MEMBER_REMOVE: {
                    final var event = DiscordGuildMemberRemove.builder()
                            .guild(Entity.fromJson(mewna.catnip(), GuildImpl.class, data.getJsonObject("guild")))
                            .member(Entity.fromJson(mewna.catnip(), MemberImpl.class, data.getJsonObject("member")))
                            .user(Entity.fromJson(mewna.catnip(), UserImpl.class, data.getJsonObject("user")))
                            .build();
                    mewna.pluginManager().processEvent(type, event);
                    break;
                }
                case EventType.AUDIO_TRACK_NO_MATCHES:
                case EventType.AUDIO_TRACK_QUEUE_MANY:
                case EventType.AUDIO_TRACK_NOW_PLAYING:
                case EventType.AUDIO_TRACK_START:
                case EventType.AUDIO_QUEUE_END:
                case EventType.AUDIO_TRACK_QUEUE: {
                    final var event = data.getJsonObject("data").mapTo(NekoTrackEvent.class);
                    mewna.pluginManager().processEvent(type, event);
                    break;
                }
                case EventType.TWITCH_STREAM_START: {
                    final var streamer = data.getJsonObject("data").getJsonObject("streamer").mapTo(TwitchStreamer.class);
                    final var stream = data.getJsonObject("data").getJsonObject("streamData").mapTo(TwitchStreamData.class);
                    mewna.pluginManager().processEvent(type, new TwitchStreamStartEvent(streamer, stream));
                    break;
                }
                case EventType.TWITCH_STREAM_END: {
                    final var streamer = data.getJsonObject("data").getJsonObject("streamer").mapTo(TwitchStreamer.class);
                    mewna.pluginManager().processEvent(type, new TwitchStreamEndEvent(streamer));
                    break;
                }
            }
        }
    }
}

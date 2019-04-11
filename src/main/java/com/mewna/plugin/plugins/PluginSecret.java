package com.mewna.plugin.plugins;

import com.mewna.Mewna;
import com.mewna.accounts.Account;
import com.mewna.catnip.entity.message.Message;
import com.mewna.catnip.entity.message.Message.Attachment;
import com.mewna.data.DiscordCache;
import com.mewna.data.Player;
import com.mewna.data.Webhook;
import com.mewna.plugin.BasePlugin;
import com.mewna.plugin.commands.Command;
import com.mewna.plugin.commands.CommandContext;
import com.mewna.plugin.Plugin;
import com.mewna.plugin.commands.annotations.Owner;
import com.mewna.plugin.plugins.settings.LevelsSettings;
import com.mewna.plugin.plugins.settings.SecretSettings;
import com.mewna.plugin.plugins.settings.TwitchSettings;
import com.mewna.plugin.plugins.settings.WelcomingSettings;
import com.mewna.plugin.util.Emotes;
import com.mewna.util.MewnaFutures;
import gg.amy.singyeong.QueryBuilder;
import io.sentry.Sentry;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import javax.inject.Inject;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

import static com.mewna.util.Async.move;

/**
 * @author amy
 * @since 10/18/18.
 */
@Owner
@Plugin(name = "secret", desc = "spooky secret things :3", settings = SecretSettings.class)
public class PluginSecret extends BasePlugin {
    @Inject
    private OkHttpClient client;
    
    @Owner
    @Command(names = "secret", desc = "secret", usage = "secret", examples = "secret")
    public void secret(final CommandContext ctx) {
        ctx.sendMessage("secret");
    }
    
    @Owner
    @Command(names = "reset", desc = "secret", usage = "secret", examples = "secret")
    public void reset(final CommandContext ctx) {
        final Message msg = ctx.getMessage();
        if(msg.attachments().isEmpty() || ctx.getArgs().isEmpty()) {
            ctx.sendMessage(Emotes.NO);
        } else {
            try {
                final Attachment a = msg.attachments().get(0);
                @SuppressWarnings({"ConstantConditions", "UnnecessarilyQualifiedInnerClassAccess"})
                final String file = client.newCall(new Request.Builder().url(a.url()).get().build()).execute().body().string();
                final List<JsonObject> pages = Arrays.stream(file.split("\n")).map(JsonObject::new).collect(Collectors.toList());
                // Validate
                final List<JsonObject> validatedPages = pages.stream().filter(e -> {
                    try {
                        switch(ctx.getArgs().get(0)) {
                            case "players": {
                                e.mapTo(Player.class);
                                return true;
                            }
                            case "settings_levels": {
                                e.mapTo(LevelsSettings.class);
                                return true;
                            }
                            case "settings_welcoming": {
                                e.mapTo(WelcomingSettings.class);
                                return true;
                            }
                            default: {
                                return false;
                            }
                        }
                    } catch(final Exception ignored) {
                        return false;
                    }
                }).collect(Collectors.toList());
                if(validatedPages.size() == pages.size()) {
                    ctx.sendMessage(Emotes.YES + " Loaded " + pages.size() + " pages of reset data for table " + ctx.getArgs().get(0));
                    
                    database().getStore().sql(conn -> {
                        conn.prepareStatement("BEGIN").execute();
                        final int[] counter = {0};
                        validatedPages.forEach(page ->
                                database().getStore().sql("UPDATE " + ctx.getArgs().get(0) + " SET data = data || ?::jsonb WHERE id = '"
                                                + page.getString("id") + "';",
                                        c -> {
                                            c.setString(1, page.encode());
                                            final int i = c.executeUpdate();
                                            counter[0] += i;
                                        }));
                        if(counter[0] != validatedPages.size()) {
                            conn.prepareStatement("ROLLBACK").execute();
                            ctx.sendMessage(Emotes.NO + " Invalid page count " + counter[0] + " for table " + ctx.getArgs().get(0) + " (expected " + validatedPages.size() + "!)");
                        } else {
                            conn.prepareStatement("COMMIT").execute();
                            // Purge cache
                            validatedPages.forEach(page -> {
                                final String id = page.getString("id");
                                mewna().database().redis(r -> r.del("mewna:player:cache:" + id));
                            });
                            ctx.sendMessage(Emotes.YES + " Updated " + counter[0] + " pages for table " + ctx.getArgs().get(0));
                        }
                    });
                } else {
                    ctx.sendMessage(Emotes.NO + ' ' + (pages.size() - validatedPages.size()) + " invalid pages for table " + ctx.getArgs().get(0));
                }
            } catch(final Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    @Owner
    @Command(names = "findwebhook", desc = "secret", usage = "secret", examples = "secret")
    public void findWebhook(final CommandContext ctx) {
        if(ctx.getArgs().isEmpty()) {
            ctx.sendMessage(Emotes.NO);
        } else {
            final Optional<Webhook> maybeHook = database().getWebhook(ctx.getArgs().get(0));
            maybeHook.ifPresentOrElse(hook ->
                            ctx.sendMessage(String.format("%s -> %s (%s)", hook.getChannel(), hook.getId(), hook.getGuild())),
                    () -> {
                        final Optional<Webhook> maybeRealHook = database().getWebhookById(ctx.getArgs().get(0));
                        maybeRealHook.ifPresentOrElse(hook -> ctx.sendMessage(
                                String.format("%s -> %s (%s)", hook.getChannel(), hook.getId(), hook.getGuild())
                                ),
                                () -> ctx.sendMessage(Emotes.NO + " No webhook!"));
                    });
        }
    }
    
    @Owner
    @Command(names = "inspect", desc = "secret", usage = "secret", examples = "secret")
    public void debugInspect(final CommandContext ctx) {
        switch(ctx.getArgs().get(0).toLowerCase()) {
            case "threads": {
                final int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
                final int peakThreadCount = ManagementFactory.getThreadMXBean().getPeakThreadCount();
                final int daemonThreadCount = ManagementFactory.getThreadMXBean().getDaemonThreadCount();
                ctx.sendMessage("```CSS\n" +
                        "     [Total threads] " + threadCount + '\n' +
                        "[Non-daemon threads] " + (threadCount - daemonThreadCount) + '\n' +
                        "    [Daemon threads] " + daemonThreadCount + '\n' +
                        "      [Peak threads] " + peakThreadCount + '\n' +
                        "```");
                break;
            }
            case "player": {
                final String snowflake = ctx.getArgs().get(1)
                        .replaceAll("<@(!)?", "")
                        .replace(">", "");
                database().getOptionalPlayer(snowflake, ctx.getProfiler()).thenAccept(optionalPlayer -> {
                    if(optionalPlayer.isPresent()) {
                        final JsonObject o = JsonObject.mapFrom(optionalPlayer.get());
                        o.remove("account");
                        o.remove("votes");
                        o.remove("boxes");
                        final String json = o.encodePrettily();
                        ctx.sendMessage("```Javascript\n" + json + "\n```");
                    } else {
                        ctx.sendMessage(Emotes.NO);
                    }
                });
                break;
            }
            case "account": {
                final String snowflake = ctx.getArgs().get(1)
                        .replaceAll("<@(!)?", "")
                        .replace(">", "");
                final Optional<Account> optionalAccount = mewna().accountManager().getAccountByLinkedDiscord(snowflake);
                if(optionalAccount.isPresent()) {
                    final JsonObject o = JsonObject.mapFrom(optionalAccount.get());
                    o.remove("email");
                    final String json = o.encodePrettily();
                    ctx.sendMessage("```Javascript\n" + json + "\n```");
                } else {
                    ctx.sendMessage(Emotes.NO);
                }
                break;
            }
            default: {
                ctx.sendMessage(Emotes.NO);
                break;
            }
        }
    }
    
    @Owner
    @Command(names = "guildcast", desc = "secret", usage = "secret", examples = "secret")
    public void guildcast(final CommandContext ctx) {
        final String guildId = ctx.getGuild().id();
        mewna().singyeong().send("shards", new QueryBuilder().contains("guilds", guildId).build(),
                new JsonObject().put("henlo", guildId));
        ctx.sendMessage("Casting guild " + guildId);
    }
    
    @Owner
    @Command(names = "guildcheck", desc = "secret", usage = "secret", examples = "secret")
    public void guildcheck(final CommandContext ctx) {
        final String guildId = ctx.getGuild().id();
        
        //noinspection CodeBlock2Expr
        DiscordCache.guild(guildId).thenAccept(g -> {
            ctx.sendMessage("Checked casted guild " + guildId + " with result " + g);
        }).exceptionally(e -> {
            ctx.sendMessage("Checked casted guild " + guildId + " with exception");
            e.printStackTrace();
            return null;
        });
    }
    
    @Owner
    @Command(names = "fetch", desc = "secret", usage = "secret", examples = "secret")
    public void fetch(final CommandContext ctx) {
        final Message msg = ctx.getMessage();
        final String guildId = msg.guildId();
        final String channelId = msg.channelId();
        final String userId = msg.author().id();
        
        final var guild = DiscordCache.guild(guildId);
        final var user = DiscordCache.user(userId);
        final var channel = DiscordCache.textChannel(guildId, channelId);
        final var member = DiscordCache.member(guildId, userId);
        
        MewnaFutures.allOf(guild, user, channel, member).thenAccept(__ ->
                catnip().rest().channel()
                        .sendMessage(channelId, "" +
                                "__Results:__\n" +
                                "```\n" +
                                "  Guild: " + MewnaFutures.get(guild) + " \n" +
                                "   User: " + MewnaFutures.get(user) + " \n" +
                                "Channel: " + MewnaFutures.get(channel) + " \n" +
                                " Member: " + MewnaFutures.get(member) + " \n" +
                                "```"
                        ));
    }
    
    @Owner
    @Command(names = "dm", desc = "secret", usage = "secret", examples = "secret")
    public void dm(final CommandContext ctx) {
        catnip().rest().user().createDM(ctx.getUser().id()).thenAccept(channel -> channel.sendMessage("test!"));
    }
    
    @Owner
    @Command(names = "forcetwitchresub", desc = "secret", usage = "secret", examples = "secret")
    public void forceTwitchResub(final CommandContext ctx) {
        new Thread(() -> {
            final Set<String> ids = new HashSet<>();
            database().getStore().sql("SELECT data FROM settings_twitch;", c -> {
                final ResultSet rs = c.executeQuery();
                while(rs.next()) {
                    try {
                        final TwitchSettings s = Json.mapper.readValue(rs.getString("data"), TwitchSettings.class);
                        s.getTwitchStreamers().stream()
                                .filter(e -> e.isStreamStartMessagesEnabled() || e.isStreamEndMessagesEnabled())
                                .forEach(e -> ids.add(e.getId()));
                    } catch(final IOException e) {
                        Sentry.capture(e);
                    }
                }
            });
            ids.forEach(e -> Mewna.getInstance().singyeong().send("telepathy",
                    new QueryBuilder().build(),
                    new JsonObject().put("t", "TWITCH_SUBSCRIBE")
                            .put("d", new JsonObject().put("id", e).put("topic", "streams"))));
            catnip().rest().user().createDM(ctx.getUser().id())
                    .thenAccept(channel -> channel.sendMessage("Finished forcing twitch resub (" + ids.size() + " streamers)."));
        }).start();
    }
    
    @Owner
    @Command(names = {"blacklist", "heck-off", "heckoff"}, desc = "", usage = "", examples = "")
    public void blacklist(final CommandContext ctx) {
        if(ctx.getArgs().size() < 2) {
            ctx.sendMessage(Emotes.NO);
        } else {
            move(() -> {
                final StringBuilder sb = new StringBuilder("Result:\n");
                final String[] users = ctx.getArgs().remove(0).split(",");
                final String reason = String.join(" ", ctx.getArgs());
                for(final String user : users) {
                    final Optional<Account> maybeAccount = database().getAccountById(user);
                    if(maybeAccount.isPresent()) {
                        final Account account = maybeAccount.get();
                        account.banned(true);
                        // account.isBanned(true);
                        account.banReason(reason);
                        database().saveAccount(account);
                        sb.append(Emotes.YES).append(' ').append(user).append('\n');
                    } else {
                        sb.append(Emotes.NO).append(' ').append(user).append('\n');
                    }
                }
                ctx.sendMessage(sb.toString());
            });
        }
    }
}

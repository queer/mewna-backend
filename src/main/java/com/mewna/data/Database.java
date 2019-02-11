package com.mewna.data;

import com.mewna.Mewna;
import com.mewna.accounts.Account;
import com.mewna.accounts.timeline.TimelinePost;
import com.mewna.catnip.entity.user.User;
import com.mewna.plugin.Plugin;
import com.mewna.plugin.util.Snowflakes;
import com.mewna.servers.ServerBlogPost;
import gg.amy.pgorm.PgStore;
import gg.amy.singyeong.SafeVertxCompletableFuture;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.sentry.Sentry;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.Getter;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Transaction;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.mewna.data.PluginSettings.MAPPER;

/**
 * Database-level abstraction
 *
 * @author amy
 * @since 4/14/18.
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public class Database {
    @Getter
    private final Mewna mewna;
    @Getter
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Map<String, Class<? extends PluginSettings>> pluginSettingsByName = new HashMap<>();
    @SuppressWarnings("TypeMayBeWeakened")
    private final OkHttpClient client = new OkHttpClient();
    private boolean init;
    @Getter
    private PgStore store;
    private JedisPool jedisPool;
    
    public Database(final Mewna mewna) {
        this.mewna = mewna;
    }
    
    public void init() {
        if(init) {
            return;
        }
        init = true;
        logger.info("Connecting to Redis...");
        final JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxIdle(10);
        config.setMaxTotal(100);
        config.setMaxWaitMillis(500);
        jedisPool = new JedisPool(config, System.getenv("REDIS_HOST"));
        logger.info("Redis connection pool ready!");
        
        store = PgStore.fromEnv();
        store.connect();
        
        mapSettingsClasses();
        
        premap(Player.class, Account.class, TimelinePost.class, ServerBlogPost.class);
        
        // Webhooks table is created manually, because it doesn't need to be JSON:b:
        store.sql("CREATE TABLE IF NOT EXISTS discord_webhooks (channel TEXT PRIMARY KEY NOT NULL UNIQUE, guild TEXT NOT NULL, " +
                "id TEXT NOT NULL, secret TEXT NOT NULL)");
        store.sql("CREATE INDEX IF NOT EXISTS idx_discord_webhooks_guilds ON discord_webhooks (guild);");
        store.sql("CREATE INDEX IF NOT EXISTS idx_discord_webhooks_ids ON discord_webhooks (id);");
    }
    
    //////////////
    // Internal //
    //////////////
    
    @SuppressWarnings("unchecked")
    private void mapSettingsClasses() {
        final List<Class<?>> classes = new ArrayList<>();
        new FastClasspathScanner(Plugin.class.getPackage().getName()).matchAllStandardClasses(cls -> {
            if(PluginSettings.class.isAssignableFrom(cls) && !cls.equals(PluginSettings.class)) {
                classes.add(cls);
                pluginSettingsByName.put(cls.getSimpleName().toLowerCase().replace("settings", ""),
                        (Class<? extends PluginSettings>) cls);
            }
        }).scan();
        premap(classes.toArray(new Class[0]));
    }
    
    private void premap(final Class<?>... clz) {
        for(final Class<?> c : clz) {
            logger.info("Premapping class: " + c.getName());
            store.mapSync(c);
            store.mapAsync(c);
        }
    }
    
    //////////////
    // Webhooks //
    //////////////
    
    public Optional<Webhook> getWebhook(final String channelId) {
        final OptionalHolder<Webhook> holder = new OptionalHolder<>();
        store.sql("SELECT * FROM discord_webhooks WHERE channel = ?;", p -> {
            p.setString(1, channelId);
            final ResultSet resultSet = p.executeQuery();
            if(resultSet.isBeforeFirst()) {
                resultSet.next();
                final String channel = resultSet.getString("channel");
                final String guild = resultSet.getString("guild");
                final String id = resultSet.getString("id");
                final String secret = resultSet.getString("secret");
                holder.setValue(new Webhook(channel, guild, id, secret));
            }
        });
        return holder.value;
    }
    
    public Optional<Webhook> getWebhookById(final String hookId) {
        final OptionalHolder<Webhook> holder = new OptionalHolder<>();
        store.sql("SELECT * FROM discord_webhooks WHERE id = ?;", p -> {
            p.setString(1, hookId);
            final ResultSet resultSet = p.executeQuery();
            if(resultSet.isBeforeFirst()) {
                resultSet.next();
                final String channel = resultSet.getString("channel");
                final String guild = resultSet.getString("guild");
                final String id = resultSet.getString("id");
                final String secret = resultSet.getString("secret");
                holder.setValue(new Webhook(channel, guild, id, secret));
            }
        });
        return holder.value;
    }
    
    public void addWebhook(final Webhook webhook) {
        store.sql("INSERT INTO discord_webhooks (channel, guild, id, secret) VALUES (?, ?, ?, ?);", p -> {
            p.setString(1, webhook.getChannel());
            p.setString(2, webhook.getGuild());
            p.setString(3, webhook.getId());
            p.setString(4, webhook.getSecret());
            p.execute();
        });
    }
    
    public List<Webhook> getAllWebhooks(final String guildId) {
        final List<Webhook> webhooks = new ArrayList<>();
        store.sql("SELECT * FROM discord_webhooks WHERE guild = ?;", p -> {
            p.setString(1, guildId);
            final ResultSet resultSet = p.executeQuery();
            if(resultSet.isBeforeFirst()) {
                while(resultSet.next()) {
                    final String channel = resultSet.getString("channel");
                    final String guild = resultSet.getString("guild");
                    final String id = resultSet.getString("id");
                    final String secret = resultSet.getString("secret");
                    webhooks.add(new Webhook(channel, guild, id, secret));
                }
            }
        });
        return webhooks;
    }
    
    public void deleteWebhook(final String channel) {
        // TODO: Twitch settings need to be checked / updated
        store.sql("DELETE FROM discord_webhooks WHERE channel = ?;", p -> {
            p.setString(1, channel);
            p.execute();
        });
    }
    
    public void deleteWebhookById(final String id) {
        // TODO: Twitch settings need to be checked / updated
        final Optional<Webhook> webhookById = getWebhookById(id);
        if(webhookById.isPresent()) {
            final Webhook webhook = webhookById.get();
            store.sql("DELETE FROM discord_webhooks WHERE id = ?;", p -> {
                p.setString(1, id);
                p.execute();
            });
            try {
                //noinspection UnnecessarilyQualifiedInnerClassAccess
                client.newCall(new Request.Builder()
                        .delete()
                        .url("https://discordapp.com/api/v6/webhooks/" + webhook.getId() + '/' + webhook.getSecret())
                        .build()).execute().close();
            } catch(final IOException e) {
                e.printStackTrace();
            }
        }
    }
    
    //////////////
    // Settings //
    //////////////
    
    @SuppressWarnings("unchecked")
    public <T extends PluginSettings> Class<T> getSettingsClassByType(final String type) {
        return (Class<T>) pluginSettingsByName.get(type);
    }
    
    private <T extends PluginSettings> CompletableFuture<Optional<T>> getSettingsByType(final Class<T> type, final String id) {
        return store.mapAsync(type).load(id);
    }
    
    public <T extends PluginSettings> CompletableFuture<T> getOrBaseSettings(final String type, final String id) {
        final Class<T> cls = getSettingsClassByType(type);
        if(cls == null) {
            throw new IllegalArgumentException("Type '" + type + "' not a valid settingClass.");
        }
        return getOrBaseSettings(cls, id);
    }
    
    @SuppressWarnings({"unchecked", "OptionalAssignedToNull"})
    public <T extends PluginSettings> CompletableFuture<T> getOrBaseSettings(final Class<T> type, final String id) {
        if(!store.isMappedSync(type) && !store.isMappedAsync(type)) {
            throw new IllegalArgumentException("Attempted to get settings of type " + type.getName() + ", but it's not mapped!");
        }
        final Future<T> future = Future.future();
        
        getSettingsByType(type, id)
                .exceptionally(e -> {
                    Sentry.capture(e);
                    return null;
                })
                .thenApply(maybeSettings -> {
                    // This is a valid thing to do - a null value is returned
                    // when the settings *are not present due to an error*, ie
                    // in an exceptional case that generally shouldn't ever happen
                    if(maybeSettings == null) {
                        // future.fail("Failing due to database issues");
                        throw new IllegalStateException("Failing due to database issues");
                    } else if(maybeSettings.isPresent()) {
                        final T maybe = maybeSettings.get();
                        // We're joining inside of NON-vx threads here, so I'm pretty sure this is safe?
                        // Honestly I just couldn't think of a better way to do it......
                        return maybe.refreshCommands().otherRefresh().thenAccept(settings ->
                                saveSettings(settings).exceptionally(e -> {
                                    Sentry.capture(e);
                                    return null;
                                }).thenAccept(__ -> future.complete((T) settings))).join();
                    } else {
                        try {
                            final T base = type.getConstructor(String.class).newInstance(id);
                            saveSettings(base);
                            // future.complete(base);
                            return base;
                        } catch(final IllegalAccessException | NoSuchMethodException | InvocationTargetException | InstantiationException e) {
                            Sentry.capture(e);
                            // future.fail(e);
                            throw new RuntimeException(e);
                        }
                    }
                });
        
        return SafeVertxCompletableFuture.from(mewna.vertx(), future);
    }
    
    @SuppressWarnings("unchecked")
    public <T extends PluginSettings> CompletableFuture<Void> saveSettings(final T settings) {
        // This is technically valid
        return store.mapAsync((Class<T>) settings.getClass()).save(settings);
    }
    
    /////////////
    // Players //
    /////////////
    
    public CompletableFuture<Optional<Player>> getOptionalPlayer(final String id) {
        return store.mapAsync(Player.class).load(id).exceptionally(e -> {
            Sentry.capture(e);
            // This is okay I promise ;-;
            //noinspection OptionalAssignedToNull
            return null;
        });
    }
    
    public CompletableFuture<Player> getPlayer(final User user) {
        return getOptionalPlayer(user.id())
                .thenApply(o -> {
                    // This is fine!
                    // o is null only in case of database errors
                    //noinspection OptionalAssignedToNull
                    if(o == null) {
                        throw new IllegalStateException("Database error fetching player " + user.id());
                    } else {
                        return o.orElseGet(() -> {
                            final Player base = Player.base(user.id());
                            savePlayer(base);
                            // If we don't have a player, then we also need to create an account for them
                            if(!mewna.accountManager().getAccountByLinkedDiscord(user.id()).isPresent()) {
                                mewna.accountManager().createNewDiscordLinkedAccount(base, user);
                            }
                            return base;
                        });
                    }
                })
                .exceptionally(e -> {
                    Sentry.capture(e);
                    return null;
                });
    }
    
    public CompletableFuture<Void> savePlayer(final Player player) {
        player.cleanup();
        return store.mapAsync(Player.class).save(player);
    }
    
    public void redis(final Consumer<Jedis> c) {
        try(final Jedis jedis = jedisPool.getResource()) {
            jedis.auth(System.getenv("REDIS_PASS"));
            c.accept(jedis);
        }
    }
    
    public void tredis(final Consumer<Transaction> t) {
        redis(c -> {
            final Transaction transaction = c.multi();
            t.accept(transaction);
            transaction.exec();
        });
    }
    
    //////////////
    // Accounts //
    //////////////
    
    public Optional<Account> getAccountById(final String id) {
        return store.mapSync(Account.class).load(id);
    }
    
    public Optional<Account> getAccountByDiscordId(final String id) {
        final OptionalHolder<Account> holder = new OptionalHolder<>();
        
        store.sql("SELECT data FROM " + store.mapSync(Account.class).getTableName() + " WHERE data->>'discordAccountId' = ?;", p -> {
            p.setString(1, id);
            final ResultSet resultSet = p.executeQuery();
            if(resultSet.isBeforeFirst()) {
                resultSet.next();
                final String data = resultSet.getString("data");
                try {
                    holder.setValue(MAPPER.readValue(data, Account.class));
                } catch(final IOException e) {
                    Sentry.capture(e);
                    throw new RuntimeException(e);
                }
            }
        });
        
        return holder.value;
    }
    
    public void saveAccount(final Account account) {
        store.mapSync(Account.class).save(account);
    }
    
    public void savePost(final TimelinePost post) {
        store.mapSync(TimelinePost.class).save(post);
    }
    
    public List<TimelinePost> getLast100TimelinePosts(final String id) {
        final List<TimelinePost> posts = new ArrayList<>();
        store.sql("SELECT data FROM " + store.mapSync(TimelinePost.class).getTableName() + " WHERE data->>'author' = ? ORDER BY id::bigint DESC LIMIT 100;", q -> {
            q.setString(1, id);
            final ResultSet resultSet = q.executeQuery();
            while(resultSet.next()) {
                final String data = resultSet.getString("data");
                posts.add(new JsonObject(data).mapTo(TimelinePost.class));
            }
        });
        
        return posts;
    }
    
    public List<TimelinePost> getAllTimelinePosts(final String id) {
        final List<TimelinePost> posts = new ArrayList<>();
        store.sql("SELECT data FROM " + store.mapSync(TimelinePost.class).getTableName() + " WHERE data->>'author' = ? ORDER BY id::bigint DESC;", q -> {
            q.setString(1, id);
            final ResultSet resultSet = q.executeQuery();
            while(resultSet.next()) {
                final String data = resultSet.getString("data");
                posts.add(new JsonObject(data).mapTo(TimelinePost.class));
            }
        });
        
        return posts;
    }
    
    //////////////////
    // Server blogs //
    //////////////////
    
    public Optional<ServerBlogPost> getServerBlogPostById(final String id) {
        return store.mapSync(ServerBlogPost.class).load(id);
    }
    
    public String saveNewServerBlogPost(final ServerBlogPost post) {
        post.setId(null);
        post.setBoops(new HashSet<>());
        if(post.validate()) {
            post.setId(Snowflakes.getNewSnowflake());
            store.mapSync(ServerBlogPost.class).save(post);
            return post.getId();
        } else {
            return "-1";
        }
    }
    
    public String updateServerBlogPost(final ServerBlogPost post) {
        post.setBoops(new HashSet<>());
        if(post.validate()) {
            final Optional<ServerBlogPost> prev = getServerBlogPostById(post.getId());
            if(prev.isPresent()) {
                store.mapSync(ServerBlogPost.class).save(prev.get()
                        .toBuilder()
                        .title(post.getTitle())
                        .content(post.getContent())
                        .build());
                return post.getId();
            } else {
                return "-1";
            }
        } else {
            return "-2";
        }
    }
    
    public void deleteServerBlogPost(final String id) {
        store.sql("DELETE FROM " + store.mapSync(ServerBlogPost.class).getTableName() + " WHERE id = ?;", p -> {
            p.setString(1, id);
            p.execute();
        });
    }
    
    public List<ServerBlogPost> getLast100ServerBlogPosts(final String id) {
        final List<ServerBlogPost> posts = new ArrayList<>();
        store.sql("SELECT data FROM " + store.mapSync(ServerBlogPost.class).getTableName() + " WHERE data->>'guild' = ? ORDER BY id::bigint DESC LIMIT 100;", q -> {
            q.setString(1, id);
            final ResultSet resultSet = q.executeQuery();
            while(resultSet.next()) {
                final String data = resultSet.getString("data");
                posts.add(new JsonObject(data).mapTo(ServerBlogPost.class));
            }
        });
        
        return posts;
    }
    
    public List<ServerBlogPost> getServerBlogPosts(final String id) {
        final List<ServerBlogPost> posts = new ArrayList<>();
        store.sql("SELECT data FROM " + store.mapSync(ServerBlogPost.class).getTableName() + " WHERE data->>'guild' = ? ORDER BY id::bigint DESC;", q -> {
            q.setString(1, id);
            final ResultSet resultSet = q.executeQuery();
            while(resultSet.next()) {
                final String data = resultSet.getString("data");
                posts.add(new JsonObject(data).mapTo(ServerBlogPost.class));
            }
        });
        
        return posts;
    }
    
    public JsonArray getServerBlogPostTitles(final String id) {
        final List<JsonObject> posts = new ArrayList<>();
        store.sql("SELECT data FROM " + store.mapSync(ServerBlogPost.class).getTableName() + " WHERE data->>'guild' = ? ORDER BY id::bigint DESC;", q -> {
            q.setString(1, id);
            final ResultSet resultSet = q.executeQuery();
            while(resultSet.next()) {
                final String data = resultSet.getString("data");
                final ServerBlogPost post = new JsonObject(data).mapTo(ServerBlogPost.class);
                post.setContent(null);
                final JsonObject d = JsonObject.mapFrom(post);
                final Optional<Account> account = getAccountById(post.getAuthor());
                final JsonObject author = new JsonObject();
                if(account.isPresent()) {
                    author.put("displayName", account.get().displayName())
                            .put("avatar", account.get().avatar())
                            .put("id", account.get().id());
                } else {
                    author.put("displayName", "Unknown User")
                            .put("avatar", "https://cdn.discordapp.com/embed/avatars/0.png")
                            .put("id", "0");
                }
                d.put("author", author);
                posts.add(d);
            }
        });
        
        return new JsonArray(posts);
    }
    
    //////////
    // Misc //
    //////////
    
    public String language(final String guild) {
        // TODO: Make this return a real locale value...
        return "en_US";
    }
    
    private final class OptionalHolder<T> {
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        private Optional<T> value;
        
        private OptionalHolder() {
            value = Optional.empty();
        }
        
        private void setValue(final T data) {
            value = Optional.ofNullable(data);
        }
    }
}

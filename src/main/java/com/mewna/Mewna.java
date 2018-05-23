package com.mewna;

import com.mewna.cache.DiscordCache;
import com.mewna.data.Database;
import com.mewna.data.PluginSettings;
import com.mewna.event.EventManager;
import com.mewna.jda.RestJDA;
import com.mewna.nats.NatsServer;
import com.mewna.plugin.CommandManager;
import com.mewna.plugin.PluginManager;
import com.mewna.util.Ratelimiter;
import lombok.Getter;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static spark.Spark.*;

/**
 * @author amy
 * @since 4/8/18.
 */
@SuppressWarnings("Singleton")
public final class Mewna {
    @SuppressWarnings("StaticVariableOfConcreteClass")
    private static final Mewna INSTANCE = new Mewna();
    
    @Getter
    private final EventManager eventManager = new EventManager(this);
    @Getter
    private final PluginManager pluginManager = new PluginManager(this);
    @Getter
    private final CommandManager commandManager = new CommandManager(this);
    @Getter
    private final RestJDA restJDA = new RestJDA(System.getenv("TOKEN"));
    @Getter
    private final Database database = new Database(this);
    @Getter
    private final Ratelimiter ratelimiter = new Ratelimiter(this);
    private final Logger logger = LoggerFactory.getLogger(getClass());
    @Getter
    private NatsServer nats;
    
    private Mewna() {
    }
    
    public static void main(final String[] args) {
        INSTANCE.start();
    }
    
    @SuppressWarnings("unused")
    public static Mewna getInstance() {
        return INSTANCE;
    }
    
    private void start() {
        logger.info("Starting Mewna backend...");
        eventManager.getCache().connect();
        database.init();
        pluginManager.init();
        startApiServer();
        nats = new NatsServer(this);
        nats.connect();
        logger.info("Finished starting!");
    }
    
    private void startApiServer() {
        logger.info("Starting API server...");
        port(Integer.parseInt(Optional.ofNullable(System.getenv("PORT")).orElse("80")));
        path("/cache", () -> {
            get("/user/:id", (req, res) -> new JSONObject(getCache().getUser(req.params(":id"))));
            get("/guild/:id", (req, res) -> new JSONObject(getCache().getGuild(req.params(":id"))));
            get("/guild/:id/channels", (req, res) -> new JSONArray(getCache().getGuildChannels(req.params(":id"))));
            get("/guild/:id/roles", (req, res) -> new JSONArray(getCache().getGuildRoles(req.params(":id"))));
            get("/channel/:id", (req, res) -> new JSONObject(getCache().getChannel(req.params(":id"))));
            get("/role/:id", (req, res) -> new JSONObject(getCache().getRole(req.params(":id"))));
        });
        path("/data", () -> {
    
            //noinspection CodeBlock2Expr
            path("/guild", () -> {
                //noinspection CodeBlock2Expr
                path("/:id", () -> {
                   path("/config", () -> {
                       get("/:type", (req, res) -> {
                           final PluginSettings settings = getDatabase().getOrBaseSettings(req.params(":type"), req.params(":id"));
                           return new JSONObject(settings);
                       });
                       post("/:type", (req, res) -> {
                           // TODO: Fetch old settings and use to validate, then save if it passes, otherwise return :fire:
                           return "";
                       });
                   });
                });
            });
            
            path("/commands", () -> {
                // TODO: More shit goes here
                get("/metadata", (req, res) -> new JSONArray(commandManager.getCommandMetadata()));
            });
            
            path("/plugins", () -> {
                // TODO: More shit goes here
                get("/metadata", (req, res) -> new JSONArray(pluginManager.getPluginMetadata()));
            });
            get("/player/:id", (req, res) -> new JSONObject(database.getPlayer(req.params(":id"))));
        });
    }
    
    @SuppressWarnings("WeakerAccess")
    public DiscordCache getCache() {
        return eventManager.getCache();
    }
}

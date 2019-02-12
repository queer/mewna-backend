package com.mewna.plugin.plugins;

import com.google.common.collect.ImmutableMap;
import com.mewna.data.PluginSettings;
import com.mewna.plugin.BasePlugin;
import com.mewna.plugin.Command;
import com.mewna.plugin.CommandContext;
import com.mewna.plugin.Plugin;
import com.mewna.plugin.plugins.settings.*;
import com.mewna.plugin.util.Emotes;
import io.vertx.core.json.JsonObject;

import java.util.*;

import static com.mewna.util.Async.move;

/**
 * @author amy
 * @since 2/11/19.
 */
@Plugin(name = "staff", desc = "Staff-only commands~", settings = SecretSettings.class, staff = true)
public class PluginStaff extends BasePlugin {
    private final Map<String, Class<? extends PluginSettings>> configs = ImmutableMap.copyOf(new HashMap<>() {{
        put("behaviour", BehaviourSettings.class);
        put("economy", EconomySettings.class);
        put("emotes", EmotesSettings.class);
        put("levels", LevelsSettings.class);
        put("misc", MiscSettings.class);
        put("music", MusicSettings.class);
        put("twitch", TwitchSettings.class);
        put("welcoming", WelcomingSettings.class);
    }});
    
    @Command(names = "config", desc = "staff-only", usage = "staff-only", examples = "staff-only", staff = true)
    public void config(final CommandContext ctx) {
        if(ctx.getArgs().isEmpty()) {
            ctx.sendMessage(Emotes.NO + " You need to provide a server id and a config type " +
                    "(ex. `mew.config 1234567890 behaviour`), " +
                    "or do `mew.config types` for all config types.");
        } else if(ctx.getArgs().get(0).equalsIgnoreCase("types")) {
            ctx.sendMessage("```CSS\n" +
                    "[behaviour] prefix and similar\n" +
                    "  [economy] currency symbol and commands\n" +
                    "   [emotes] commands\n" +
                    "   [levels] level-up message, role rewards, and commands\n" +
                    "     [misc] anything that doesn't fit elsewhere\n" +
                    "    [music] commands\n" +
                    "   [twitch] streamers and webhook channel\n" +
                    "[welcoming] messages and channel\n" +
                    "```");
        } else if(ctx.getArgs().size() == 2 && ctx.getArgs().get(0).matches("\\d+")
                && configs.containsKey(ctx.getArgs().get(1).toLowerCase())) {
            database().getOrBaseSettings(ctx.getArgs().get(1).toLowerCase(), ctx.getArgs().get(0)).thenAccept(settings -> {
                final JsonObject json = JsonObject.mapFrom(settings);
                final Iterable<String> split = new ArrayList<>(Arrays.asList(json.encodePrettily().split("\n")));
                final Collection<String> pages = new ArrayList<>();
                String page = "```Javascript\n";
                for(final String s : split) {
                    //noinspection StringConcatenationInLoop
                    page += s + '\n';
                    if(page.length() > 1500) {
                        page += "```";
                        pages.add("" + page);
                        page = "";
                    }
                }
                if(!page.isEmpty()) {
                    if(!page.endsWith("```")) {
                        page += "```";
                    }
                    if(!page.startsWith("```")) {
                        page = "```Javascript\n" + page;
                    }
                    pages.add("" + page);
                }
                move(() -> {
                    for(final String p : pages) {
                        // lol joins :^^^^^^^)
                        ctx.getUser().createDM().toCompletableFuture().join().sendMessage(p).toCompletableFuture().join();
                        try {
                            Thread.sleep(100L);
                        } catch(final InterruptedException ignored) {
                        }
                    }
                    ctx.sendMessage("Check your DMs ^^");
                });
            });
        } else {
            ctx.sendMessage(Emotes.NO + " You need to provide a server id and a config type " +
                    "(ex. `mew.config 1234567890 behaviour`), " +
                    "or do `mew.config types` for all config types.");
        }
    }
}

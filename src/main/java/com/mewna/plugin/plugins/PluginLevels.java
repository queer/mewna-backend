package com.mewna.plugin.plugins;

import com.mewna.cache.entity.Guild;
import com.mewna.cache.entity.Member;
import com.mewna.cache.entity.User;
import com.mewna.data.Player;
import com.mewna.plugin.BasePlugin;
import com.mewna.plugin.Command;
import com.mewna.plugin.CommandContext;
import com.mewna.plugin.Plugin;
import com.mewna.plugin.event.Event;
import com.mewna.plugin.event.EventType;
import com.mewna.plugin.event.message.MessageCreateEvent;
import com.mewna.plugin.event.plugin.levels.LevelUpEvent;
import com.mewna.plugin.plugins.settings.LevelsSettings;
import com.mewna.util.Templater;
import net.dv8tion.jda.core.EmbedBuilder;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.json.JSONObject;

import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author amy
 * @since 5/19/18.
 */
@SuppressWarnings({"unused", "WeakerAccess"})
@Plugin(name = "Levels", desc = "Allow gaining xp and leveling up by chatting.", settings = LevelsSettings.class)
public class PluginLevels extends BasePlugin {
    static boolean isLevelUp(final long oldXp, final long xp) {
        return xpToLevel(oldXp) < xpToLevel(xp);
    }
    
    static long levelToXp(final long level) {
        return Math.max(0, 100 * level + 20 * (level - 1));
    }
    
    static long fullLevelToXp(final long level) {
        long requiredXp = 0;
        for(int i = 1; i <= level; i++) {
            requiredXp += levelToXp(i);
        }
        return requiredXp;
    }
    
    static long nextLevelXp(final long xp) {
        return fullLevelToXp(xpToLevel(xp) + 1) - xp;
    }
    
    static long xpToLevel(long xp) {
        long level = 0;
        while(xp >= levelToXp(level)) {
            xp -= levelToXp(level);
            level += 1;
        }
        
        return Math.max(0, level - 1);
    }
    
    private Templater map(final LevelUpEvent event) {
        final Guild guild = event.getGuild();
        final User user = event.getUser();
        final Map<String, String> data = new HashMap<>();
        final JSONObject jGuild = new JSONObject(guild);
        for(final String key : jGuild.keySet()) {
            data.put("server." + key, jGuild.get(key).toString());
        }
        final JSONObject jUser = new JSONObject(user);
        for(final String key : jUser.keySet()) {
            data.put("user." + key, jUser.get(key).toString());
        }
        data.put("user.mention", user.asMention());
        data.put("level", event.getLevel() + "");
        data.put("xp", event.getXp() + "");
        return Templater.fromMap(data);
    }
    
    @Event(EventType.LEVEL_UP)
    public void handleLevelUp(final LevelUpEvent event) {
        final Guild guild = event.getGuild();
        final LevelsSettings settings = getMewna().getDatabase().getOrBaseSettings(LevelsSettings.class, guild.getId());
        if(settings.isLevelsEnabled()) {
            final Member member = getCache().getMember(guild, event.getUser());
            if(settings.isLevelUpMessagesEnabled()) {
                // TODO: Handle cards
                // I guess worst-case we could CDN it and pretend instead of uploading to Discord
                // but that's gross af
                if(settings.isRemovePreviousRoleRewards()) {
                    removeAndAddRoleRewards(settings, guild, member, event.getLevel(), () -> sendLevelUpMessage(settings, event, member));
                } else {
                    addRoleRewards(settings, guild, member, event.getLevel(), () -> sendLevelUpMessage(settings, event, member));
                }
            }
        }
    }
    
    private void sendLevelUpMessage(final LevelsSettings settings, final LevelUpEvent event, final Member member) {
        if(settings.isLevelsEnabled()) {
            if(settings.isLevelUpMessagesEnabled()) {
                final String message = map(event).render(settings.getLevelUpMessage());
                getRestJDA().sendMessage(event.getChannel(), message).queue();
            }
        }
    }
    
    private void removeAndAddRoleRewards(final LevelsSettings settings, final Guild guild, final Member member,
                                         final long level, final Runnable callback) {
        // Check for roles at this level
        final List<String> rewards = settings.getLevelRoleRewards().entrySet().stream()
                .filter(e -> e.getValue() == level).map(Entry::getKey).collect(Collectors.toList());
        if(!rewards.isEmpty()) {
            // If we have some, remove lower roles then add in the rest
            final List<String> removeRoles = settings.getLevelRoleRewards().entrySet().stream()
                    .filter(e -> e.getValue() < level).map(Entry::getKey).collect(Collectors.toList());
            getRestJDA().addAndRemoveManyRolesForMember(guild, member, rewards, removeRoles).queue(__ -> callback.run());
        }
    }
    
    private void addRoleRewards(final LevelsSettings settings, final Guild guild, final Member member, final long level,
                                final Runnable callback) {
        // Check for roles at this level
        final List<String> rewards = settings.getLevelRoleRewards().entrySet().stream()
                .filter(e -> e.getValue() == level).map(Entry::getKey).collect(Collectors.toList());
        if(!rewards.isEmpty()) {
            getRestJDA().addManyRolesToMember(guild, member, rewards.toArray(new String[0])).queue(__ -> callback.run());
        }
    }
    
    @Event(EventType.MESSAGE_CREATE)
    public void handleChatMessage(final MessageCreateEvent event) {
        final Guild guild = event.getGuild();
        final LevelsSettings settings = getMewna().getDatabase().getOrBaseSettings(LevelsSettings.class, guild.getId());
        if(!settings.isLevelsEnabled()) {
            return;
        }
        final User author = event.getAuthor();
        final Player player = getDatabase().getPlayer(author);
        getLogger().trace("Handling chat message for player {} in {}", author.getId(), guild.getId());
        
        // Calc. cooldown
        final ImmutablePair<Boolean, Long> localRes = getMewna().getRatelimiter()
                .checkUpdateRatelimit(event.getAuthor().getId(), "chat-xp-local:" + guild.getId(),
                        TimeUnit.MINUTES.toMillis(1));
        final ImmutablePair<Boolean, Long> globalRes = getMewna().getRatelimiter()
                .checkUpdateRatelimit(event.getAuthor().getId(), "chat-xp-global", TimeUnit.MINUTES.toMillis(1));
        
        if(!localRes.left) {
            final long oldXp = player.getXp(guild);
            final long xp = getXp(player);
            player.incrementLocalXp(guild, xp);
            getDatabase().savePlayer(player);
            getLogger().debug("Local XP: {} in {}: {} -> {}", author.getId(), guild.getId(), oldXp, oldXp + xp);
            if(isLevelUp(oldXp, oldXp + xp)) {
                getLogger().debug("{} in {}: Level up to {}", author.getId(), guild.getId(), xpToLevel(oldXp + xp));
                // Emit level-up event so we can process it
                getMewna().getNats().pushBackendEvent(EventType.LEVEL_UP, new JSONObject().put("user", author.getId())
                        .put("guild", guild.getId()).put("level", xpToLevel(oldXp + xp)).put("xp", oldXp + xp)
                        .put("channel", event.getChannel().getId()));
            }
        } else {
            getLogger().debug("Local XP: {} in {} ratelimited ({}ms)", author.getId(), guild.getId(),
                    getMewna().getRatelimiter().getRatelimitTime(author.getId(), "chat-xp-local:" + guild.getId(),
                            TimeUnit.MINUTES.toMillis(1)));
        }
        if(!globalRes.left) {
            player.incrementGlobalXp(getXp(player));
            getDatabase().savePlayer(player);
            // Level-up notifications here?
        }
    }
    
    @Command(names = {"rank", "level"}, desc = "Check your rank, or someone else's rank.", usage = "rank [@mention]",
            examples = {"rank", "rank @someone"})
    public void rank(final CommandContext ctx) {
        final Guild guild = ctx.getGuild();
        final LevelsSettings settings = getMewna().getDatabase().getOrBaseSettings(LevelsSettings.class, guild.getId());
        if(!settings.isLevelsEnabled()) {
            getRestJDA().sendMessage(ctx.getChannel(), "Levels are not enabled in this server.").queue();
            return;
        }
        final long xp;
        final long level;
        final String name;
        final String discrim;
        final String avatar;
        if(ctx.getMentions().isEmpty()) {
            xp = ctx.getPlayer().getXp(guild);
            name = ctx.getUser().getName();
            discrim = ctx.getUser().getDiscriminator();
            avatar = ctx.getUser().getAvatarURL();
        } else {
            final User user = ctx.getMentions().get(0);
            final Player player = getDatabase().getPlayer(user);
            xp = player.getXp(guild);
            name = user.getName();
            discrim = user.getDiscriminator();
            avatar = user.getAvatarURL();
        }
        level = xpToLevel(xp);
        final EmbedBuilder builder = new EmbedBuilder().setTitle(String.format("%s#%s's rank", name, discrim))
                .addField("Level", "Level " + level, true).addBlankField(true)
                .addField("XP", xp + " / " + (fullLevelToXp(xpToLevel(xp)) + levelToXp(xpToLevel(xp) + 1)) + " (" + nextLevelXp(xp) + "xp left)", true)
                .addField("Rank", getPlayerRankInGuild(guild, ctx.getUser()) + " / "
                        + getAllRankedPlayersInGuild(guild), true)
                .setThumbnail(avatar);
        getRestJDA().sendMessage(ctx.getChannel(), builder.build()).queue();
    }
    
    @Command(names = {"leaderboards", "ranks", "levels", "leaderboard", "rankings"}, desc = "View the guild leaderboards.",
            usage = "leaderboards", examples = "leaderboards")
    public void ranks(final CommandContext ctx) {
        final Guild guild = ctx.getGuild();
        final LevelsSettings settings = getMewna().getDatabase().getOrBaseSettings(LevelsSettings.class, guild.getId());
        if(!settings.isLevelsEnabled()) {
            getRestJDA().sendMessage(ctx.getChannel(), "Levels are not enabled in this server.").queue();
            return;
        }
        getRestJDA().sendMessage(ctx.getChannel(), "https://amy.chat/leaderboards/" + guild.getId()).queue();
    }
    
    private int getAllRankedPlayersInGuild(final Guild guild) {
        final int[] count = {0};
        getDatabase().getStore().sql("SELECT COUNT(*) AS count FROM players WHERE data->'guildXp'->'" + guild.getId() + "' IS NOT NULL;", p -> {
            final ResultSet resultSet = p.executeQuery();
            if(resultSet.isBeforeFirst()) {
                resultSet.next();
            }
            count[0] = resultSet.getInt("count");
        });
        return count[0];
    }
    
    private int getPlayerRankInGuild(final Guild guild, final User player) {
        final int[] rank = {-1};
        final String guildId = guild.getId();
        final String playerId = player.getId();
        getDatabase().getStore().sql("SELECT rank FROM (SELECT row_number() OVER () AS rank, data FROM players " +
                "WHERE data->'guildXp'->'" + guildId + "' IS NOT NULL " +
                "ORDER BY (data->'guildXp'->>'" + guildId + "')::integer DESC) AS _q " +
                "WHERE data->>'id' = '" + playerId + "';", p -> {
            final ResultSet resultSet = p.executeQuery();
            if(resultSet.isBeforeFirst()) {
                resultSet.next();
                rank[0] = resultSet.getInt("rank");
            } else {
                rank[0] = 1;
            }
        });
        return rank[0];
    }
    
    private long getXp(final Player player) {
        return 10 + getRandom().nextInt(10);
    }
}

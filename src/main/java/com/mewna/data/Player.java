package com.mewna.data;

import com.mewna.cache.entity.Guild;
import com.mewna.plugin.CommandContext;
import gg.amy.pgorm.annotations.Index;
import gg.amy.pgorm.annotations.PrimaryKey;
import gg.amy.pgorm.annotations.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * @author amy
 * @since 4/10/18.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table("players")
@Index({"id", "guildXp", "guildBalances"})
@SuppressWarnings("unused")
public class Player {
    private static final String LOREM_IPSUM = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " +
            "Cras vehicula mi urna, nec tincidunt erat tincidunt eget. " +
            "Maecenas pretium consectetur metus.";
    @PrimaryKey
    private String id;
    private long balance;
    private long lastDaily;
    private long dailyStreak;
    private Map<String, Long> guildXp = new HashMap<>();
    private long globalXp;
    private long points;
    private String aboutText;
    
    public static Player base(final String id) {
        return new Player(id, 0L, 0L, 0L, new HashMap<>(), 0L, 0L,
                /*"A mysterious stranger."*/ LOREM_IPSUM);
    }
    
    // Daily
    
    public void updateLastDaily() {
        lastDaily = System.currentTimeMillis();
    }
    
    public void incrementDailyStreak() {
        dailyStreak += 1;
    }
    
    public void resetDailyStreak() {
        dailyStreak = 0;
    }
    
    // Balance
    
    public void incrementBalance(final long amount) {
        balance += amount;
        if(balance < 0L) {
            balance = 0L;
        }
    }
    
    // XP
    
    public long getXp(final Guild guild) {
        final String id = guild.getId();
        if(guildXp.containsKey(id)) {
            return guildXp.get(id);
        } else {
            guildXp.put(id, 0L);
            return 0L;
        }
    }
    
    public long getXp(final CommandContext ctx) {
        return getXp(ctx.getGuild());
    }
    
    @SuppressWarnings("WeakerAccess")
    public void incrementLocalXp(final String id, final long amount) {
        guildXp.put(id, guildXp.getOrDefault(id, 0L) + amount);
        if(guildXp.get(id) < 0L) {
            guildXp.put(id, 0L);
        }
    }
    
    public void incrementLocalXp(final Guild guild, final long amount) {
        incrementLocalXp(guild.getId(), amount);
    }
    
    public void incrementGlobalXp(final long amount) {
        globalXp += amount;
    }
    
    // Scoring
    
    public long calculateScore() {
        return 123_456_789;
    }
}

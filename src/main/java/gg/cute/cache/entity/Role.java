package gg.cute.cache.entity;

import com.datastax.driver.mapping.annotations.PartitionKey;
import com.datastax.driver.mapping.annotations.Table;
import lombok.*;

/**
 * @author amy
 * @since 4/8/18.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(keyspace = "cute", name = "roles")
public class Role {
    @PartitionKey
    private String id;
    private String name;
    private int color;
}

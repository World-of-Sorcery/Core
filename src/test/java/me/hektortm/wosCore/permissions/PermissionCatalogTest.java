package me.hektortm.wosCore.permissions;

import me.hektortm.wosCore.util.Permissions;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** What Core sends to wos-api as a plugin's nodes, and that Core's own list is complete. */
class PermissionCatalogTest {

    private static PluginDescriptionFile yml(String text) throws Exception {
        return new PluginDescriptionFile(new StringReader(text));
    }

    @Test
    void declaredNodesThenUndeclaredCommandNodes() throws Exception {
        List<PermissionCatalog.Node> nodes = PermissionCatalog.nodesOf(yml("""
                name: Test
                version: '1'
                main: x.Test
                commands:
                  pay:
                    permission: economy.pay
                  stats:
                    permission: stats.use;stats.admin
                  open: {}
                permissions:
                  economy.pay:
                    description: Pay another player.
                    default: true
                  economy.baltop:
                    description: See the richest players.
                """));
        assertThat(nodes).containsExactly(
                new PermissionCatalog.Node("economy.pay", "Pay another player.", "true"),
                new PermissionCatalog.Node("economy.baltop", "See the richest players.", "op"),
                new PermissionCatalog.Node("stats.use", "Use /stats.", "op"),
                new PermissionCatalog.Node("stats.admin", "Use /stats.", "op"));
    }

    @Test
    void defaultsUsePluginYmlSpelling() {
        assertThat(PermissionCatalog.defaultName(PermissionDefault.NOT_OP)).isEqualTo("not op");
        assertThat(PermissionCatalog.defaultName(PermissionDefault.FALSE)).isEqualTo("false");
    }

    /** Every node Core checks is declared (with a description), and nothing else is. */
    @Test
    void pluginYmlDeclaresExactlyTheEnum() throws Exception {
        PluginDescriptionFile d;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            d = new PluginDescriptionFile(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Set<String> declared = d.getPermissions().stream()
                .filter(p -> p.getChildren().isEmpty()) // a parent (core.log) only bundles others
                .map(org.bukkit.permissions.Permission::getName).collect(Collectors.toSet());
        Set<String> checked = Arrays.stream(Permissions.values()).map(Permissions::getPermission).collect(Collectors.toSet());
        assertThat(declared).isEqualTo(checked);
        assertThat(d.getPermissions()).allSatisfy(p -> assertThat(p.getDescription()).isNotBlank());
    }
}

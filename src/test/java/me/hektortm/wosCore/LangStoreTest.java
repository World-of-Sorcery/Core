package me.hektortm.wosCore;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The message store: YAML defaults as dotted keys, and staff edits winning over them. */
class LangStoreTest {

    private static Map<String, String> flatten(String yaml) throws Exception {
        return LangStore.flatten(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void nestedYamlBecomesDottedKeys() throws Exception {
        Map<String, String> m = flatten("""
                error:
                  cooldown: "Wait %time%s"
                  inventory: "No space"
                cost: "§7Cost: §e%amount%"
                """);
        assertThat(m).containsExactly(
                Map.entry("error.cooldown", "Wait %time%s"),
                Map.entry("error.inventory", "No space"),
                Map.entry("cost", "§7Cost: §e%amount%"));
    }

    @Test
    void anEditWinsOverTheDefault() {
        LangStore store = new LangStore();
        store.putDefaults("guis", Map.of("cost", "Cost", "price", "Price"));
        store.setEdits(Map.of("guis", Map.of("cost", "Kosten")));
        assertThat(store.message("guis", "cost")).isEqualTo("Kosten");
        assertThat(store.message("guis", "price")).isEqualTo("Price");
        assertThat(store.message("guis", "missing")).isNull();
        assertThat(store.message("nope", "cost")).isNull();
        assertThat(store.effective("guis")).containsEntry("cost", "Kosten").containsEntry("price", "Price");
    }

    @Test
    void anEditedMessageWhoseDefaultIsGoneStillShows() {
        LangStore store = new LangStore();
        store.setEdits(Map.of("guis", Map.of("old", "Still here")));
        assertThat(store.hasFile("guis")).isTrue();
        assertThat(store.message("guis", "old")).isEqualTo("Still here");
    }
}

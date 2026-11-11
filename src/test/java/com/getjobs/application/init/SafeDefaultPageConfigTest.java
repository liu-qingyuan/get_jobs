package com.getjobs.application.init;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SafeDefaultPageConfigTest {
    @Test
    void bundledDefaultsPreserveSchemaWithoutAssumingPersonalFactsOrPermission() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode defaults;
        try (var input = new ClassPathResource("defaults/page-config.json").getInputStream()) {
            defaults = mapper.readTree(input);
        }

        assertEquals(Set.of("boss_config", "liepin_config", "job51_config", "zhilian_config", "ai", "boss_blacklist"), fields(defaults));
        assertEquals(0, defaults.path("boss_blacklist").size());
        for (String table : List.of("boss_config", "liepin_config", "job51_config", "zhilian_config", "ai")) {
            assertEquals(1, defaults.path(table).size(), table);
        }

        JsonNode boss = defaults.path("boss_config").get(0);
        assertEquals(Set.of("debugger", "wait_time", "keywords", "city_code", "industry", "job_type",
                "experience", "degree", "salary", "scale", "stage", "say_hi", "expected_salary_min",
                "expected_salary_max", "enable_ai", "send_img_resume", "filter_dead_hr", "dead_status",
                "allow_repeat_chat", "repeat_chat_messages"), fields(boss));
        assertEquals(1, boss.path("debugger").asInt());
        assertEquals(0, boss.path("enable_ai").asInt());
        assertEquals(0, boss.path("send_img_resume").asInt());
        assertEquals(0, boss.path("allow_repeat_chat").asInt());
        assertEquals("", boss.path("say_hi").asText());
        assertEquals("0", boss.path("city_code").asText());
        assertEquals("不限", boss.path("job_type").asText());
        for (String field : List.of("industry", "experience", "degree", "salary", "scale", "stage", "dead_status")) {
            assertEquals("[]", boss.path(field).asText(), field);
        }
        assertEquals(0, boss.path("filter_dead_hr").asInt());
        assertTrue(boss.path("expected_salary_min").isNull());
        assertTrue(boss.path("expected_salary_max").isNull());

        JsonNode liepin = defaults.path("liepin_config").get(0);
        assertEquals(Set.of("keywords", "city", "salary_code"), fields(liepin));
        // Liepin's loader accepts comma-separated words; JSON array text would retain quotation marks.
        assertEquals("大模型,机器学习,具身智能", liepin.path("keywords").asText());
        assertEquals("", liepin.path("city").asText());
        assertEquals("", liepin.path("salary_code").asText());
        JsonNode job51 = defaults.path("job51_config").get(0);
        assertEquals(Set.of("keywords", "job_area", "salary"), fields(job51));
        assertEquals("[]", job51.path("job_area").asText());
        assertEquals("[]", job51.path("salary").asText());
        JsonNode zhilian = defaults.path("zhilian_config").get(0);
        assertEquals(Set.of("keywords", "city_code", "salary"), fields(zhilian));
        assertEquals("0", zhilian.path("city_code").asText());
        assertEquals("0", zhilian.path("salary").asText());

        List<String> directions = List.of("大模型", "机器学习", "具身智能");
        for (String table : List.of("boss_config", "job51_config", "zhilian_config")) {
            String keywords = defaults.path(table).get(0).path("keywords").asText();
            assertEquals(directions, mapper.readValue(keywords, new TypeReference<List<String>>() {}), table);
        }

        JsonNode ai = defaults.path("ai").get(0);
        assertEquals(Set.of("introduce", "prompt"), fields(ai));
        assertEquals("", ai.path("introduce").asText());
        String prompt = ai.path("prompt").asText();
        assertEquals(5, prompt.split("%s", -1).length - 1);
        assertFalse(prompt.contains("8年"));
        assertFalse(prompt.contains("我熟练"));
    }

    private static Set<String> fields(JsonNode object) {
        Set<String> names = new HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }
}

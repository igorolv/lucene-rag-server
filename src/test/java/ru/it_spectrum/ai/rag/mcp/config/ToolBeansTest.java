package ru.it_spectrum.ai.rag.mcp.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import ru.it_spectrum.ai.rag.mcp.tools.AdminTools;
import ru.it_spectrum.ai.rag.mcp.tools.BrowseTools;
import ru.it_spectrum.ai.rag.mcp.tools.DebugTools;
import ru.it_spectrum.ai.rag.mcp.tools.CollectionTools;
import ru.it_spectrum.ai.rag.mcp.tools.LookupTools;
import ru.it_spectrum.ai.rag.mcp.tools.RecordResources;
import ru.it_spectrum.ai.rag.mcp.tools.SearchTools;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every tool group is a bean by default. Started without the MCP server, as {@link StepBeansTest} is.
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.server.enabled=false",
        "spring.ai.mcp.server.annotation-scanner.enabled=false",
        "logging.config=classpath:logback-cli.xml"})
class ToolBeansTest {

    @Autowired
    ApplicationContext context;

    @Test
    void everyToolGroupIsABean() {
        for (Class<?> type : new Class<?>[]{CollectionTools.class, SearchTools.class, RecordResources.class,
                LookupTools.class, AdminTools.class, BrowseTools.class, DebugTools.class}) {
            assertThat(context.getBeanNamesForType(type)).as(type.getSimpleName()).hasSize(1);
        }
    }
}

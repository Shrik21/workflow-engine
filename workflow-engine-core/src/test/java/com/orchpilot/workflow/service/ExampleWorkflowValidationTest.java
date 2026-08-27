package com.orchpilot.workflow.service;

import com.orchpilot.workflow.dto.WorkflowRequest;
import com.orchpilot.workflow.expression.SpelExpressionEvaluator;
import com.orchpilot.workflow.model.PluginStatus;
import com.orchpilot.workflow.model.PluginVersion;
import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.node.DecisionNodeExecutor;
import com.orchpilot.workflow.node.EndNodeExecutor;
import com.orchpilot.workflow.node.FormNodeExecutor;
import com.orchpilot.workflow.node.StartNodeExecutor;
import com.orchpilot.workflow.node.DefaultWorkflowNodeRegistry;
import com.orchpilot.workflow.node.NodeExecutorResolver;
import com.orchpilot.workflow.node.WorkflowNodeExecutor;
import com.orchpilot.workflow.plugin.PluginHandle;
import com.orchpilot.workflow.plugin.PluginRegistry;
import com.orchpilot.workflow.repository.PluginVersionRepository;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.plugin.PluginDescriptor;
import com.orchpilot.workflow.sdk.plugin.PluginType;
import com.orchpilot.workflow.support.testplugin.EchoPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Checks that the workflow definitions shipped in {@code examples/} actually pass the engine's validator.
 *
 * <p>Worth a test of its own: a sample workflow that a reader copies and cannot publish is worse than no sample at
 * all, and the failure would only ever be noticed by whoever tried it.
 *
 * <p>The plugin registry is stubbed to report the three sample plugins as loaded, because validation of a plugin node
 * legitimately depends on the plugin being installed. Everything else — graph structure, reachability, decision
 * branches, expressions, cron expressions, timezones, required configuration — is the real validator.
 */
@EnabledIf("examplesDirectoryExists")
class ExampleWorkflowValidationTest {

    private static final Path EXAMPLES = Path.of("..", "examples");

    private DefaultWorkflowValidator validator;
    private WorkflowDtoMapper mapper;
    private JsonMapper jsonMapper;

    /** Skips rather than fails when run outside the repository layout, e.g. from an unpacked artifact. */
    static boolean examplesDirectoryExists() {
        return Files.isDirectory(EXAMPLES);
    }

    @BeforeEach
    void setUp() {
        mapper = new WorkflowDtoMapper();
        jsonMapper = JsonMapper.builder().build();

        // The form node's collaborators are irrelevant here: this test validates graphs, and validation asks the
        // registry which node types exist rather than executing any of them.
        List<WorkflowNodeExecutor> builtIns = List.of(new StartNodeExecutor(),
                new FormNodeExecutor(mock(com.orchpilot.workflow.forms.FormNodeBinding.class),
                        mock(com.orchpilot.workflow.forms.FormVariableMapper.class),
                        mock(com.orchpilot.workflow.task.HumanTaskService.class),
                        mock(com.orchpilot.workflow.task.TaskAssignmentResolver.class)),
                new DecisionNodeExecutor(), new EndNodeExecutor());

        PluginRegistry pluginRegistry = stubRegistry();
        PluginVersionRepository versionRepository = mock(PluginVersionRepository.class);
        when(versionRepository.findByPluginIdAndVersion(anyString(), anyString())).thenAnswer(invocation ->
                Optional.of(installedVersion(invocation.getArgument(0), invocation.getArgument(1))));

        @SuppressWarnings("unchecked")
        ObjectProvider<NodeExecutorResolver> resolvers = mock(ObjectProvider.class);
        DefaultWorkflowNodeRegistry nodeRegistry = new DefaultWorkflowNodeRegistry(builtIns, resolvers);

        // The binding resolves no form, so a form node in an example workflow produces a warning rather than an
        // error. That is the point of the check being a warning: these examples ship without published forms.
        // A publish policy over an empty catalogue, which is what an engine with no registry configured has.
        // The examples must stay publishable on such an engine, so this is the state worth testing them against.
        com.orchpilot.workflow.pluginserver.PluginCatalogSyncService catalog =
                mock(com.orchpilot.workflow.pluginserver.PluginCatalogSyncService.class);
        when(catalog.entry(anyString())).thenReturn(Optional.empty());
        com.orchpilot.workflow.pluginserver.InstalledPluginRepository installedPlugins =
                mock(com.orchpilot.workflow.pluginserver.InstalledPluginRepository.class);
        when(installedPlugins.findById(anyString())).thenReturn(Optional.empty());

        validator = new DefaultWorkflowValidator(nodeRegistry, pluginRegistry, versionRepository,
                new SpelExpressionEvaluator(), mock(com.orchpilot.workflow.forms.FormNodeBinding.class),
                new com.orchpilot.workflow.pluginserver.PluginPublishPolicy(catalog, installedPlugins));
    }

    @ParameterizedTest
    @DisplayName("the shipped example workflows are publishable")
    @ValueSource(strings = {"employee-approval-workflow.json", "order-fulfilment-workflow.json"})
    void exampleWorkflowsValidate(String fileName) throws Exception {
        Path file = EXAMPLES.resolve(fileName);
        assertTrue(Files.exists(file), "missing example: " + file.toAbsolutePath());

        WorkflowRequest request = jsonMapper.readValue(Files.readString(file), WorkflowRequest.class);
        Workflow workflow = new Workflow();
        workflow.setId("example");
        workflow.setName(request.name());
        workflow.setDescription(request.description());
        workflow.setNodes(mapper.toNodes(request.nodes()));
        workflow.setConnections(mapper.toConnections(request.connections()));
        workflow.setTriggers(mapper.toTriggers(request.triggers()));
        workflow.setVariables(request.variables() == null ? new LinkedHashMap<>() : request.variables());

        List<String> errors = validator.validate(workflow);

        assertTrue(errors.isEmpty(), fileName + " does not validate: " + errors);
    }

    // ---------------------------------------------------------------- stubs

    private static PluginVersion installedVersion(String pluginId, String version) {
        PluginVersion installed = new PluginVersion();
        installed.setId(PluginVersion.idFor(pluginId, version));
        installed.setPluginId(pluginId);
        installed.setVersion(version);
        installed.setStatus(PluginStatus.ACTIVE);
        installed.setNodeTypes(new ArrayList<>(nodeTypesFor(pluginId)));
        return installed;
    }

    private static List<String> nodeTypesFor(String pluginId) {
        return switch (pluginId) {
            case "sendgrid" -> List.of("SENDGRID_EMAIL");
            case "restapi" -> List.of("REST_API_CALL");
            case "slack" -> List.of("SLACK_MESSAGE");
            default -> List.of("ECHO");
        };
    }

    /**
     * A registry that reports the three sample plugins as loaded, with the configuration schemas they actually
     * publish, so required-configuration validation is exercised for real.
     */
    private static PluginRegistry stubRegistry() {
        Map<String, PluginHandle> handles = new LinkedHashMap<>();
        for (String pluginId : List.of("sendgrid", "restapi", "slack")) {
            handles.put(pluginId, handleFor(pluginId));
        }
        PluginRegistry registry = mock(PluginRegistry.class);
        when(registry.find(anyString(), anyString())).thenAnswer(invocation ->
                Optional.ofNullable(handles.get(invocation.<String>getArgument(0))));
        when(registry.findDefault(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(handles.get(invocation.<String>getArgument(0))));
        when(registry.findByNodeType(anyString())).thenAnswer(invocation -> {
            String nodeType = invocation.getArgument(0);
            return handles.values().stream()
                    .filter(handle -> handle.nodeTypes().contains(nodeType))
                    .findFirst();
        });
        return registry;
    }

    private static PluginHandle handleFor(String pluginId) {
        List<NodeDefinition> definitions = new ArrayList<>();
        for (String nodeType : nodeTypesFor(pluginId)) {
            definitions.add(NodeDefinition.builder(nodeType)
                    .displayName(nodeType)
                    .configurationSchema(schemaFor(nodeType))
                    .build());
        }
        PluginDescriptor descriptor = PluginDescriptor.builder(pluginId, "1.0.0")
                .name(pluginId)
                .type(PluginType.NODE)
                .nodeDefinitions(definitions)
                .build();
        return new PluginHandle(descriptor, new EchoPlugin(), null, null,
                installedVersion(pluginId, "1.0.0"), Path.of(System.getProperty("java.io.tmpdir")));
    }

    /** The required-field lists the real sample plugins declare. */
    private static Map<String, Object> schemaFor(String nodeType) {
        List<String> required = switch (nodeType) {
            case "SENDGRID_EMAIL" -> List.of("apiKeySecret", "from", "to", "subject", "body");
            case "REST_API_CALL" -> List.of("method", "url");
            case "SLACK_MESSAGE" -> List.of("botTokenSecret", "channel", "text");
            default -> List.of("message");
        };
        return Map.of("type", "object", "properties", Map.of(), "required", required);
    }
}

package com.agentframework.orchestrator.council;

import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.gp.PlanDecompositionPredictor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for Council Taste Profile integration in {@link CouncilService}.
 *
 * <p>Verifies that the predictor is invoked during pre-planning sessions and
 * that the {@link CouncilReport} is enriched with GP predictions when available.</p>
 */
@ExtendWith(MockitoExtension.class)
class CouncilServiceTasteProfileTest {

    @Mock private ChatClient chatClient;
    @Mock private CouncilPromptLoader promptLoader;
    @Mock private PlanDecompositionPredictor decompositionPredictor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CouncilService service;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callResponse;

    private static final CouncilProperties PROPS =
            new CouncilProperties(true, 3, true, true, false);

    @BeforeEach
    void setUp() {
        service = new CouncilService(
                chatClient, promptLoader, PROPS, objectMapper,
                Optional.empty(),
                Optional.of(decompositionPredictor));

        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callResponse = mock(ChatClient.CallResponseSpec.class);
    }

    @Test
    @DisplayName("conductPrePlanningSession enriches CouncilReport with GP prediction when predictor returns result")
    void conductPrePlanningSession_withPredictor_enrichesReport() {
        // GP predictor returns a meaningful prediction
        when(decompositionPredictor.predict(anyInt(), anyBoolean(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(Optional.of(new GpPrediction(0.72, 0.04)));

        // Stub the ChatClient chain for: selectMembers → member consultations → synthesize
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponse);

        when(promptLoader.loadSelectorPrompt()).thenReturn("select-prompt");
        when(promptLoader.loadMemberPrompt(anyString())).thenReturn("member-prompt");
        when(promptLoader.loadManagerPrompt()).thenReturn("manager-prompt");

        // selectMembers returns 2 members; member consultation returns text; synthesize returns null-parsed report
        when(callResponse.content()).thenReturn(
                "[\"be-manager\", \"security-specialist\"]",  // 1st: selector
                "BE architecture advice",                       // 2nd: be-manager
                "Security advice",                              // 3rd: security-specialist
                // 4th: synthesizer — returns a minimal CouncilReport JSON
                "{\"selectedMembers\":[\"be-manager\"],\"architectureDecisions\":[\"Use Repository\"],"
                + "\"techStackRationale\":null,\"securityConsiderations\":null,"
                + "\"dataModelingGuidelines\":null,\"apiDesignGuidelines\":null,"
                + "\"testingStrategy\":null,\"memberInsights\":{},"
                + "\"predictedReward\":null,\"predictionUncertainty\":null,\"decompositionHint\":null}");

        CouncilReport report = service.conductPrePlanningSession("Build a REST API");

        assertThat(report).isNotNull();
        // GP enrichment: predictedReward should be set
        assertThat(report.predictedReward()).isCloseTo(0.72, within(1e-9));
        assertThat(report.predictionUncertainty()).isCloseTo(0.04, within(1e-9));
        assertThat(report.decompositionHint()).isNotNull().contains("GP:");

        verify(decompositionPredictor).predict(anyInt(), anyBoolean(), anyBoolean(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("conductPrePlanningSession returns unmodified report when predictor is in cold-start mode")
    void conductPrePlanningSession_coldStart_reportUnchanged() {
        // Cold-start: predictor returns empty
        when(decompositionPredictor.predict(anyInt(), anyBoolean(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(Optional.empty());

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponse);

        when(promptLoader.loadSelectorPrompt()).thenReturn("select-prompt");
        when(promptLoader.loadMemberPrompt(anyString())).thenReturn("member-prompt");
        when(promptLoader.loadManagerPrompt()).thenReturn("manager-prompt");

        when(callResponse.content()).thenReturn(
                "[\"be-manager\"]",
                "BE advice",
                "{\"selectedMembers\":[\"be-manager\"],\"architectureDecisions\":[],"
                + "\"techStackRationale\":null,\"securityConsiderations\":null,"
                + "\"dataModelingGuidelines\":null,\"apiDesignGuidelines\":null,"
                + "\"testingStrategy\":null,\"memberInsights\":{},"
                + "\"predictedReward\":null,\"predictionUncertainty\":null,\"decompositionHint\":null}");

        CouncilReport report = service.conductPrePlanningSession("Simple script");

        assertThat(report).isNotNull();
        // Cold start: all GP fields remain null
        assertThat(report.predictedReward()).isNull();
        assertThat(report.predictionUncertainty()).isNull();
        assertThat(report.decompositionHint()).isNull();
    }
}

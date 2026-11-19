package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AgentToolsTest {
  final ObjectMapper json = new ObjectMapper();
  final Identity who = new Identity("user", "tenant", "BUSINESS");

  JsonNode response(Object message) {
    return json.valueToTree(
        Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", message))));
  }

  Object call(String name, String arguments) {
    return Map.of(
        "role",
        "assistant",
        "tool_calls",
        List.of(
            Map.of(
                "id",
                "tool-1",
                "type",
                "function",
                "function",
                Map.of("name", name, "arguments", arguments))));
  }

  @Test
  void factualQueryMustInvokeBoundToolAndReturnKnownEvidence() {
    var model = mock(ModelGateway.class);
    when(model.enabled()).thenReturn(true);
    when(model.model()).thenReturn("base");
    when(model.chat(anyString(), anyString(), anyString()))
        .thenReturn("{\"intent\":\"direct_chat\"}");
    when(model.request(anyString(), any()))
        .thenReturn(response(call("search_project_documents", "{\"query\":\"PVDF 1050A\"}")))
        .thenReturn(response(Map.of("role", "assistant", "content", "型号来自企业资料 [e-1]")));
    var repo = mock(BusinessRepository.class);
    var retrieval = mock(RetrievalService.class);
    when(retrieval.search(eq(who), eq("project"), eq("PVDF 1050"), any(), eq(8), eq(true)))
        .thenReturn(List.of(Map.of("evidence_id", "e-1", "body", "PVDF 1050")));
    var agents = new AgentService(retrieval, repo, model, json);
    try {
      var result = agents.answer(who, "project", "PVDF 1050");
      assertThat(result.get("route")).isEqualTo("retrieval");
      assertThat(result.get("answer").toString()).contains("[e-1]");
      verify(retrieval).search(eq(who), eq("project"), eq("PVDF 1050"), any(), eq(8), eq(true));
    } finally {
      agents.stop();
    }
  }

  @Test
  void toolCannotOverrideProjectOrIdentity() {
    var model = mock(ModelGateway.class);
    when(model.enabled()).thenReturn(true);
    when(model.model()).thenReturn("base");
    when(model.chat(anyString(), anyString(), anyString()))
        .thenReturn("{\"intent\":\"retrieval\"}");
    when(model.request(anyString(), any()))
        .thenReturn(
            response(
                call(
                    "search_project_documents",
                    "{\"query\":\"资料\",\"project_id\":\"other-project\"}")));
    var retrieval = mock(RetrievalService.class);
    var agent = new AgentService(retrieval, mock(BusinessRepository.class), model, json);
    try {
      assertThatThrownBy(() -> agent.answer(who, "project", "公司资料"))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("Schema");
    } finally {
      agent.stop();
    }
    verifyNoInteractions(retrieval);
  }

  @Test
  void unknownCitationCannotBeReturned() {
    var model = mock(ModelGateway.class);
    when(model.enabled()).thenReturn(true);
    when(model.model()).thenReturn("base");
    when(model.chat(anyString(), anyString(), anyString()))
        .thenReturn("{\"intent\":\"retrieval\"}");
    when(model.request(anyString(), any()))
        .thenReturn(response(call("search_project_documents", "{\"query\":\"资料\"}")))
        .thenReturn(response(Map.of("role", "assistant", "content", "参考 [unknown]")));
    var retrieval = mock(RetrievalService.class);
    when(retrieval.search(any(), anyString(), anyString(), any(), anyInt(), anyBoolean()))
        .thenReturn(List.of(Map.of("evidence_id", "known", "body", "text")));
    var agent = new AgentService(retrieval, mock(BusinessRepository.class), model, json);
    try {
      assertThatThrownBy(() -> agent.answer(who, "project", "公司资料"))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("未知证据");
    } finally {
      agent.stop();
    }
  }

  @Test
  void parallelQueryFailureCannotAppearAsAnEmptySuccessfulSearch() {
    var model = mock(ModelGateway.class);
    when(model.enabled()).thenReturn(false);
    var retrieval = mock(RetrievalService.class);
    when(retrieval.search(any(), anyString(), anyString(), any(), anyInt(), anyBoolean()))
        .thenThrow(
            error(org.springframework.http.HttpStatus.BAD_GATEWAY, "temporary retrieval failure"));
    var agent = new AgentService(retrieval, mock(BusinessRepository.class), model, json);
    try {
      assertThatThrownBy(() -> agent.answer(who, "project", "之前项目的规格"))
          .isInstanceOf(ResponseStatusException.class);
    } finally {
      agent.stop();
    }
  }
}

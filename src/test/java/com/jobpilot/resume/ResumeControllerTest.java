package com.jobpilot.resume;

import com.jobpilot.common.CorsFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResumeControllerTest {
    @Test void catalogAndRawImportUseTheSamePublicApiAsTheDesktopClient() throws Exception {
        ResumeService service=mock(ResumeService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ResumeController(service)).build();
        when(service.list()).thenReturn(new ResumeService.Catalog(List.of(),1));
        mvc.perform(get("/api/resumes")).andExpect(jsonPath("$.data.limit").value(1));
        ResumeDocument document=new ResumeDocument();document.setId("fixture-id");document.setName("简历.txt");
        when(service.importFile(eq("简历.txt"),any(byte[].class),isNull())).thenReturn(document);
        mvc.perform(post("/api/resumes/import").queryParam("filename","简历.txt").contentType(MediaType.APPLICATION_OCTET_STREAM).content("简历文本"))
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.name").value("简历.txt"));
        when(service.get("bad")).thenThrow(new IllegalArgumentException("简历标识无效"));
        mvc.perform(get("/api/resumes/bad")).andExpect(jsonPath("$.success").value(false));
    }

    @Test void externalPagesCannotReadOrChangeResumeAndAiData() throws Exception {
        CorsFilter filter=new CorsFilter();
        for(String path:List.of("/api/resumes","/api/ai/config","/api/resumes/import","/api/updates/install")){
            var request=new MockHttpServletRequest("POST",path);request.setServerName("127.0.0.1");request.setServerPort(9527);
            request.addHeader("Origin","https://unrelated.example");var response=new MockHttpServletResponse();
            AtomicBoolean invoked=new AtomicBoolean();filter.doFilter(request,response,(req,res)->invoked.set(true));
            assertThat(response.getStatus()).isEqualTo(403);assertThat(invoked).isFalse();
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        }
    }

    @Test void localUiAndKnownActivationPageKeepWorkingAndRebindingHostsAreDenied() throws Exception {
        var filter=new CorsFilter();
        for(String origin:List.of("http://127.0.0.1:9527","https://getjobs.dpdns.org")){
            var request=new MockHttpServletRequest("POST","/api/license/activate");request.setServerName("127.0.0.1");request.setServerPort(9527);request.addHeader("Origin",origin);
            var response=new MockHttpServletResponse();AtomicBoolean invoked=new AtomicBoolean();
            filter.doFilter(request,response,(req,res)->invoked.set(true));assertThat(invoked).isTrue();
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
        }
        var request=new MockHttpServletRequest("GET","/api/resumes");request.setServerName("rebind.example");request.addHeader("Origin","http://rebind.example");
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->{throw new AssertionError("must reject rebinding");});
        assertThat(response.getStatus()).isEqualTo(403);
    }
}

package backend.test;

import backend.ai.GeminiConfig;
import backend.ai.GenerationException;
import java.nio.file.Files;
import java.util.Map;

public final class GeminiConfigTest {
    public static void main(String[] args) throws Exception {
        var folder=Files.createTempDirectory("softnote-gemini-config-");
        var file=folder.resolve("gemini.properties");
        try {
            var cloud=GeminiConfig.load(file,Map.of("SOFTNOTE_GEMINI_API_KEY","cloud_test_key","SOFTNOTE_GEMINI_MODEL","test-model"));
            check(cloud.timeoutSeconds()==45 && cloud.apiKey().equals("cloud_test_key"),"Cloud works without config file");
            check(!cloud.toString().contains("cloud_test_key"),"Diagnostics redact API key");
            System.out.println("PASS environment-only deployment configuration and redacted diagnostics");
            Files.writeString(file,"gemini.apiKey=local_test_key\ngemini.model=local-model\ngemini.timeoutSeconds=30\n");
            var local=GeminiConfig.load(file,Map.of());
            check(local.model().equals("local-model")&&local.timeoutSeconds()==30,"Local properties supported");
            var override=GeminiConfig.load(file,Map.of("SOFTNOTE_GEMINI_API_KEY","override_key","SOFTNOTE_GEMINI_MODEL","cloud-model","SOFTNOTE_GEMINI_TIMEOUT_SECONDS","50"));
            check(override.apiKey().equals("override_key")&&override.model().equals("cloud-model")&&override.timeoutSeconds()==50,"Environment overrides local file");
            System.out.println("PASS local properties and environment precedence");
            rejects(()->GeminiConfig.load(file,Map.of("SOFTNOTE_GEMINI_API_KEY","")),"key problem");
            rejects(()->GeminiConfig.load(file,Map.of("SOFTNOTE_GEMINI_MODEL","invalid/model")),"valid Gemini model");
            for(String timeout:new String[]{"", "0", "56", "not-a-number"})
                rejects(()->GeminiConfig.load(file,Map.of("SOFTNOTE_GEMINI_TIMEOUT_SECONDS",timeout)),"1–55");
            Files.writeString(file,"gemini.apiKey=\\uINVALID");
            rejects(()->GeminiConfig.load(file,Map.of()),"Could not read");
            System.out.println("PASS missing key, invalid model, timeout bounds and malformed properties fail clearly");
            System.out.println("GeminiConfigTest: ALL PASSED (no API calls)");
        } finally {Files.deleteIfExists(file);Files.deleteIfExists(folder);}
    }
    private static void rejects(Work work,String message)throws Exception {
        try{work.run();throw new AssertionError("Expected configuration failure");}
        catch(GenerationException failure){check(failure.getMessage().contains(message),"Clear configuration error");}
    }
    @FunctionalInterface private interface Work{void run()throws Exception;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}

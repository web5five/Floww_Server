package com.floww.server.auth.wallet;

import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "floww.auth.wallet", name = {"enabled", "example-enabled"}, havingValue = "true")
final class WalletSigninExampleController {
    @GetMapping(value = "/wallet-signin-example/", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<byte[]> page() throws IOException {
        return asset("index.html", MediaType.TEXT_HTML);
    }

    @GetMapping(value = "/wallet-signin-example/app.js", produces = "text/javascript")
    ResponseEntity<byte[]> script() throws IOException {
        return asset("app.js", MediaType.parseMediaType("text/javascript"));
    }

    @GetMapping(value = "/wallet-signin-example/style.css", produces = "text/css")
    ResponseEntity<byte[]> style() throws IOException {
        return asset("style.css", MediaType.parseMediaType("text/css"));
    }

    private ResponseEntity<byte[]> asset(String name, MediaType type) throws IOException {
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
                .body(new ClassPathResource("wallet-signin-example/" + name).getInputStream().readAllBytes());
    }
}

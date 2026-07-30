package dev.fogmap.fog;

import dev.fogmap.fog.FogDtos.SyncRequest;
import dev.fogmap.fog.FogDtos.SyncResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fog")
public class FogController {

    private final FogService fogService;

    public FogController(FogService fogService) {
        this.fogService = fogService;
    }

    @PostMapping("/sync")
    public SyncResponse sync(Authentication authentication, @Valid @RequestBody SyncRequest request) {
        return fogService.sync((Long) authentication.getPrincipal(), request);
    }
}

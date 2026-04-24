package com.algo.trade.controller;

import com.algo.trade.data.ZerodhaOptionDatasetAppendService;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DataMaintenanceController {

    private static final Logger log = LoggerFactory.getLogger(DataMaintenanceController.class);

    private final ZerodhaOptionDatasetAppendService appendService;

    public DataMaintenanceController(ZerodhaOptionDatasetAppendService appendService) {
        this.appendService = appendService;
    }

    @PostMapping("/data-maintenance/append-zerodha-options")
    public ResponseEntity<?> appendZerodhaOptions(
            @RequestBody(required = false) ZerodhaOptionDatasetAppendService.AppendRequest request) {
        log.info("Data maintenance append-zerodha-options requested: {}", request);
        try {
            ZerodhaOptionDatasetAppendService.AppendResult result = appendService.append(request);
            log.info("Data maintenance append-zerodha-options completed: status={}, instruments={}, candlesDownloaded={}, candlesAppended={}",
                    result.status(), result.instrumentsDownloaded(), result.candlesDownloaded(),
                    result.candlesAppended());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException ex) {
            log.warn("Data maintenance append-zerodha-options rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IOException ex) {
            log.warn("Data maintenance append-zerodha-options failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("Data maintenance append-zerodha-options failed: {}", ex.getMessage(), ex);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }
}

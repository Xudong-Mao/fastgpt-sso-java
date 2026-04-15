package org.example.controller;

import org.example.service.HrSyncStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * HR 数据同步 Controller
 * 接收泛微 OA 推送的组织架构和人员数据
 * 接口规范参考：《HR同步接口说明书》
 */
@RestController
@RequestMapping("/sso/getdata")
public class HrSyncController {

    private static final Logger log = LoggerFactory.getLogger(HrSyncController.class);

    @Autowired
    private HrSyncStoreService hrSyncStoreService;

    /**
     * 分部同步
     * POST /sso/getdata/subcompany
     * 参数: data - JSON字符串，包含 action 和分部字段
     */
    @PostMapping("/subcompany")
    public ResponseEntity<Map<String, String>> syncSubcompany(@RequestParam("data") String data) {
        log.info("HR同步-分部: data={}", data);
        try {
            Map<String, Object> parsed = parseData(data);
            String action = getStringValue(parsed, "action");
            hrSyncStoreService.syncSubcompany(action, parsed);
            return ResponseEntity.ok(successResult());
        } catch (Exception e) {
            log.error("HR同步-分部失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(failResult("json数据处理异常"));
        }
    }

    /**
     * 部门同步
     * POST /sso/getdata/dept
     * 参数: data - JSON字符串，包含 action 和部门字段
     */
    @PostMapping("/dept")
    public ResponseEntity<Map<String, String>> syncDept(@RequestParam("data") String data) {
        log.info("HR同步-部门: data={}", data);
        try {
            Map<String, Object> parsed = parseData(data);
            String action = getStringValue(parsed, "action");
            hrSyncStoreService.syncDepartment(action, parsed);
            return ResponseEntity.ok(successResult());
        } catch (Exception e) {
            log.error("HR同步-部门失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(failResult("json数据处理异常"));
        }
    }

    /**
     * 岗位同步
     * POST /sso/getdata/hrmpostion
     * 参数: data - JSON字符串，包含 action 和岗位字段
     */
    @PostMapping("/hrmpostion")
    public ResponseEntity<Map<String, String>> syncJobTitle(@RequestParam("data") String data) {
        log.info("HR同步-岗位: data={}", data);
        try {
            Map<String, Object> parsed = parseData(data);
            String action = getStringValue(parsed, "action");
            hrSyncStoreService.syncJobTitle(action, parsed);
            return ResponseEntity.ok(successResult());
        } catch (Exception e) {
            log.error("HR同步-岗位失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(failResult("json数据处理异常"));
        }
    }

    /**
     * 人员同步
     * POST /sso/getdata/hrmwork
     * 参数: data - JSON字符串，包含 action 和人员字段
     */
    @PostMapping("/hrmwork")
    public ResponseEntity<Map<String, String>> syncUser(@RequestParam("data") String data) {
        log.info("HR同步-人员: data={}", data);
        try {
            Map<String, Object> parsed = parseData(data);
            String action = getStringValue(parsed, "action");
            hrSyncStoreService.syncUser(action, parsed);
            return ResponseEntity.ok(successResult());
        } catch (Exception e) {
            log.error("HR同步-人员失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(failResult("json数据处理异常"));
        }
    }

    // ========================= 工具方法 =========================

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseData(String data) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(data, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("JSON解析失败: " + e.getMessage(), e);
        }
    }

    private String getStringValue(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    private Map<String, String> successResult() {
        Map<String, String> result = new HashMap<>();
        result.put("status", "成功");
        result.put("msg", "无");
        return result;
    }

    private Map<String, String> failResult(String msg) {
        Map<String, String> result = new HashMap<>();
        result.put("status", "失败");
        result.put("msg", msg);
        return result;
    }
}

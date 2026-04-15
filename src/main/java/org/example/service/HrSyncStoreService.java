package org.example.service;

import org.example.dto.UserListItem;
import org.example.dto.OrgListItem;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HR 数据同步存储服务
 * 接收泛微OA推送的组织架构和人员数据，供 SSO 接口查询
 */
@Service
public class HrSyncStoreService {

    /** 用户数据: loginid -> UserListItem */
    private final Map<String, UserListItem> userStore = new ConcurrentHashMap<>();

    /** 组织数据: id -> OrgListItem */
    private final Map<String, OrgListItem> orgStore = new ConcurrentHashMap<>();

    /** 分部数据: id -> name (用于关联) */
    private final Map<String, String> subcompanyStore = new ConcurrentHashMap<>();

    /** 部门数据: id -> 部门信息 */
    private final Map<String, DeptInfo> deptStore = new ConcurrentHashMap<>();

    // ========================= 用户同步 =========================

    /**
     * 同步用户数据（增删改）
     */
    public void syncUser(String action, Map<String, Object> userData) {
        String loginId = getStringValue(userData, "loginid");
        if (loginId == null || loginId.isEmpty()) {
            return;
        }

        switch (action) {
            case "add":
            case "update":
                UserListItem user = new UserListItem();
                user.setUsername(loginId);
                user.setMemberName(getStringValue(userData, "lastname"));
                user.setContact(getStringValue(userData, "email"));

                // 将部门ID转为组织列表
                String deptId = getStringValue(userData, "departmentid");
                List<String> orgList = new ArrayList<>();
                if (deptId != null && !deptId.isEmpty()) {
                    orgList.add(deptId);
                }
                user.setOrg(orgList);
                userStore.put(loginId, user);
                break;

            case "delete":
                userStore.remove(loginId);
                break;

            default:
                break;
        }
    }

    /**
     * 获取所有用户列表
     */
    public List<UserListItem> getUserList() {
        return new ArrayList<>(userStore.values());
    }

    /**
     * 根据登录名获取用户
     */
    public UserListItem getUserByUsername(String username) {
        return userStore.get(username);
    }

    // ========================= 组织同步 =========================

    /**
     * 同步分部数据
     */
    public void syncSubcompany(String action, Map<String, Object> data) {
        String id = getStringValue(data, "id");
        if (id == null) return;

        switch (action) {
            case "add":
            case "update":
                String name = getStringValue(data, "subcompanyname");
                String supId = getStringValue(data, "supsubcomid");
                subcompanyStore.put(id, name);

                OrgListItem org = new OrgListItem();
                org.setId("sub_" + id);
                org.setName(name);
                org.setParentId(supId != null && !supId.isEmpty() ? "sub_" + supId : "");
                orgStore.put("sub_" + id, org);
                break;

            case "delete":
                subcompanyStore.remove(id);
                orgStore.remove("sub_" + id);
                break;

            default:
                break;
        }
    }

    /**
     * 同步部门数据
     */
    public void syncDepartment(String action, Map<String, Object> data) {
        String id = getStringValue(data, "id");
        if (id == null) return;

        switch (action) {
            case "add":
            case "update":
                DeptInfo deptInfo = new DeptInfo();
                deptInfo.id = id;
                deptInfo.mark = getStringValue(data, "departmentmark");
                deptInfo.name = getStringValue(data, "departmentname");
                deptInfo.subcompanyId = getStringValue(data, "subcompanyid1");
                deptInfo.supDeptId = getStringValue(data, "supdepid");
                deptStore.put(id, deptInfo);

                OrgListItem org = new OrgListItem();
                org.setId("dept_" + id);
                org.setName(deptInfo.mark != null ? deptInfo.mark : deptInfo.name);
                // 上级部门优先，否则挂在所属分部下
                org.setParentId(
                    deptInfo.supDeptId != null && !deptInfo.supDeptId.isEmpty()
                        ? "dept_" + deptInfo.supDeptId
                        : (deptInfo.subcompanyId != null ? "sub_" + deptInfo.subcompanyId : "")
                );
                orgStore.put("dept_" + id, org);
                break;

            case "delete":
                deptStore.remove(id);
                orgStore.remove("dept_" + id);
                break;

            default:
                break;
        }
    }

    /**
     * 同步岗位数据
     */
    public void syncJobTitle(String action, Map<String, Object> data) {
        String id = getStringValue(data, "id");
        if (id == null) return;

        switch (action) {
            case "add":
            case "update":
                String name = getStringValue(data, "jobtitlemark");
                OrgListItem org = new OrgListItem();
                org.setId("job_" + id);
                org.setName(name);
                org.setParentId("");
                orgStore.put("job_" + id, org);
                break;

            case "delete":
                orgStore.remove("job_" + id);
                break;

            default:
                break;
        }
    }

    /**
     * 获取所有组织列表
     */
    public List<OrgListItem> getOrgList() {
        return new ArrayList<>(orgStore.values());
    }

    // ========================= 工具方法 =========================

    private String getStringValue(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null) return null;
        return String.valueOf(value);
    }

    /**
     * 部门信息内部类
     */
    private static class DeptInfo {
        String id;
        String mark;
        String name;
        String subcompanyId;
        String supDeptId;
    }
}

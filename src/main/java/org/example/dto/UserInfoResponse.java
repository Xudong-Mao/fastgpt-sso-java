package org.example.dto;

/**
 * FastGPT 约定的 getUserInfo 响应结构。
 */
public class UserInfoResponse {
    private boolean success;
    private String message;
    private String username;
    private String avatar;
    private String contact;
    private String memberName;

    public static UserInfoResponse success(UserInfo userInfo) {
        UserInfoResponse response = new UserInfoResponse();
        response.success = true;
        response.message = "";
        response.username = userInfo.getUsername();
        response.avatar = userInfo.getAvatar();
        response.contact = userInfo.getContact();
        response.memberName = userInfo.getMemberName();
        return response;
    }

    public static UserInfoResponse error(String message) {
        UserInfoResponse response = new UserInfoResponse();
        response.success = false;
        response.message = message;
        return response;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public String getUsername() {
        return username;
    }

    public String getAvatar() {
        return avatar;
    }

    public String getContact() {
        return contact;
    }

    public String getMemberName() {
        return memberName;
    }
}

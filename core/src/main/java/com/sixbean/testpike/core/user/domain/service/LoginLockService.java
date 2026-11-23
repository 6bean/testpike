package com.sixbean.testpike.core.user.domain.service;


public interface LoginLockService {

    
    void applyLock(long userId, String username, int failCount, int lockMinutes);

    
    static String lockMessage(String username, int failCount, int lockMinutes) {
        return "账号 " + username + " 因连续 " + failCount + " 次登录失败已锁定，"
                + lockMinutes + " 分钟后自动解锁，非本人操作请及时修改密码";
    }

 
    static String dedupKey(long userId, String day) {
        return "LOGIN_LOCKED:" + userId + ":" + day;
    }
}

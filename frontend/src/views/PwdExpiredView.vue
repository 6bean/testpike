<template>
  <!-- tp-page-root-exempt: password-expiry screen renders its own centered
       full-viewport card layout (no .layout-main chrome); edge-gap rule N/A. -->
  <div class="pwd-expired-page" data-test="pwd-expired-page">
    <div class="pwd-card">
      <div class="pwd-header">
        <el-icon :size="40" color="var(--tp-warning)"><Lock /></el-icon>
        <h2 class="pwd-title">{{ t('profile.expiredTitle') }}</h2>
        <p class="pwd-subtitle">
          {{ t('profile.expiredSubtitle') }}
        </p>
      </div>
      <el-form label-position="top" class="pwd-form">
        <el-form-item :label="t('profile.oldPwd')">
          <el-input
            v-model="form.oldPassword"
            type="password"
            show-password
            :placeholder="t('profile.oldPwdCurrentPlaceholder')"
            data-test="pwd-expired-old"
          />
        </el-form-item>
        <el-form-item :label="t('profile.newPwd')">
          <el-input
            v-model="form.newPassword"
            type="password"
            show-password
            :placeholder="t('profile.newPwdPlaceholder2')"
            data-test="pwd-expired-new"
          />
        </el-form-item>
        <el-form-item :label="t('profile.confirmPwd')">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            show-password
            :placeholder="t('profile.confirmPwdPlaceholder')"
            data-test="pwd-expired-confirm"
          />
        </el-form-item>
        <el-button
          type="primary"
          class="pwd-submit"
          :loading="submitting"
          data-test="pwd-expired-submit"
          @click="onSubmit"
        >
          {{ t('profile.submitBtn') }}
        </el-button>
        <el-button text class="pwd-logout" data-test="pwd-expired-logout" @click="onLogout">
          {{ t('profile.logoutBtn') }}
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Lock } from '@element-plus/icons-vue'
import { changeMyPassword } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()

const authStore = useAuthStore()
const submitting = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

async function onSubmit(): Promise<void> {
  if (!form.oldPassword) {
    ElMessage.warning(t('profile.oldPwdRequired'))
    return
  }
  if (!/^(?=.*[A-Za-z])(?=.*\d).{8,64}$/.test(form.newPassword)) {
    ElMessage.warning(t('profile.newPwdWeak2'))
    return
  }
  if (form.newPassword !== form.confirmPassword) {
    ElMessage.warning(t('profile.pwdMismatch'))
    return
  }
  submitting.value = true
  try {
    await changeMyPassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
    ElMessage.success(t('profile.expiredChangeOk'))
    await authStore.logout()
    window.location.hash = '#/login'
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : t('profile.expiredChangeFail'))
  } finally {
    submitting.value = false
  }
}

async function onLogout(): Promise<void> {
  await authStore.logout()
  window.location.hash = '#/login'
}
</script>

<style scoped>
.pwd-expired-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--tp-bg-page, #0d1117);
}
.pwd-card {
  width: 400px;
  padding: 32px;
  border-radius: 12px;
  background: var(--tp-bg-card, #161b22);
  border: 1px solid var(--tp-border-color, #30363d);
}
.pwd-header {
  text-align: center;
  margin-bottom: 16px;
}
.pwd-title {
  margin: 8px 0 4px;
  font-size: 20px;
}
.pwd-subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--tp-text-secondary, #8b949e);
}
.pwd-submit {
  width: 100%;
  margin-top: 8px;
}
.pwd-logout {
  width: 100%;
  margin-top: 8px;
}
/* Vertical stack of full-width buttons: Element Plus's default sibling rule
   (.el-button+.el-button { margin-left: 12px }) shifts the logout button and
   its label 12px right, misaligning it with the submit button. Zero it here. */
.pwd-form .el-button + .el-button {
  margin-left: 0;
}
</style>

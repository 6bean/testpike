<template>
  <div class="page-root" data-test="profile-page">
    <div class="page-card profile-card">
      <!-- stage-3: the page name rides the global topbar only; the
 in-page title row (and its subtitle carrying an internal code)
 is retired with it. -->
      <el-tabs v-model="activeTab" class="profile-tabs" data-test="profile-tabs">
        
        <el-tab-pane :label="t('profile.tabInfo')" name="info" data-test="profile-tab-info">
          <div class="profile-form">
            <div class="avatar-row">
              <el-avatar
                :size="72"
                :src="form.avatar || undefined"
                class="avatar-preview"
                data-test="profile-avatar-preview"
              >
                {{ (form.nickname || authStore.user?.username || 'U').charAt(0) }}
              </el-avatar>
              <div class="avatar-tip">{{ t('profile.avatarTip') }}</div>
            </div>
            <el-form label-width="90px" class="profile-form-body">
              <el-form-item :label="t('profile.loginName')">
                <el-input :model-value="authStore.user?.username" disabled data-test="profile-username" />
              </el-form-item>
              <el-form-item :label="t('profile.nickName')">
                <el-input
                  v-model="form.nickname"
                  maxlength="100"
                  :placeholder="t('profile.nickPlaceholder')"
                  data-test="profile-nick-input"
                />
              </el-form-item>
              <el-form-item :label="t('profile.avatarUrl')">
                <el-input
                  v-model="form.avatar"
                  maxlength="200"
                  :placeholder="t('profile.avatarPlaceholder')"
                  clearable
                  data-test="profile-avatar-input"
                />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" plain :loading="saving" data-test="profile-save" @click="onSaveProfile">
                  {{ t('profile.saveBtn') }}
                </el-button>
              </el-form-item>
            </el-form>
          </div>
        </el-tab-pane>

        
        <el-tab-pane :label="t('profile.tabPassword')" name="password" data-test="profile-tab-pwd">
          <el-form label-width="110px" class="profile-form-body profile-form-narrow">
            <el-form-item :label="t('profile.oldPwd')">
              <el-input
                v-model="pwd.oldPassword"
                type="password"
                show-password
                :placeholder="t('profile.oldPwdPlaceholder')"
                data-test="profile-old-pwd"
              />
            </el-form-item>
            <el-form-item :label="t('profile.newPwd')">
              <el-input
                v-model="pwd.newPassword"
                type="password"
                show-password
                :placeholder="t('profile.newPwdPlaceholder')"
                data-test="profile-new-pwd"
              />
            </el-form-item>
            <el-form-item :label="t('profile.confirmPwd')">
              <el-input
                v-model="pwd.confirmPassword"
                type="password"
                show-password
                :placeholder="t('profile.confirmPwdPlaceholder')"
                data-test="profile-confirm-pwd"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="changing" data-test="profile-change-pwd-btn" @click="onChangePassword">
                {{ t('profile.changeBtn') }}
              </el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { changeMyPassword } from '@/api/auth'

const { t } = useI18n()

const authStore = useAuthStore()

const activeTab = ref('info')
const saving = ref(false)
const changing = ref(false)

const form = reactive<{ nickname: string; avatar: string }>({ nickname: '', avatar: '' })
const pwd = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

onMounted(() => {
  form.nickname = authStore.user?.nickName || authStore.user?.username || ''
  form.avatar = authStore.user?.avatar || ''
})


async function onSaveProfile(): Promise<void> {
  const nick = form.nickname.trim()
  if (!nick) {
    ElMessage.warning(t('profile.nickRequired'))
    return
  }
  if (nick.length > 100) {
    ElMessage.warning(t('profile.nickTooLong'))
    return
  }
  const avatar = form.avatar.trim()
  if (avatar.length > 200) {
    ElMessage.warning(t('profile.avatarTooLong'))
    return
  }
  saving.value = true
  try {
    await authStore.updateProfile({ displayName: nick, avatar: avatar || undefined })
    ElMessage.success(t('profile.saveOk'))
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : t('profile.saveFail'))
  } finally {
    saving.value = false
  }
}


async function onChangePassword(): Promise<void> {
  if (!pwd.oldPassword) {
    ElMessage.warning(t('profile.oldPwdRequired'))
    return
  }
  if (!/^(?=.*[A-Za-z])(?=.*\d).{8,64}$/.test(pwd.newPassword)) {
    ElMessage.warning(t('profile.newPwdWeak'))
    return
  }
  if (pwd.newPassword !== pwd.confirmPassword) {
    ElMessage.warning(t('profile.pwdMismatch'))
    return
  }
  changing.value = true
  try {
    await changeMyPassword({ oldPassword: pwd.oldPassword, newPassword: pwd.newPassword })
    pwd.oldPassword = ''
    pwd.newPassword = ''
    pwd.confirmPassword = ''
    ElMessage.success(t('profile.changeOk'))
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : t('profile.changeFail'))
  } finally {
    changing.value = false
  }
}
</script>

<style scoped lang="scss">
.profile-card {
}

.profile-tabs {
  margin-top: 8px;
}

.profile-form {
  display: flex;
  gap: 28px;
  align-items: flex-start;

  .avatar-row {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 10px;
    padding-top: 8px;
  }

  .avatar-preview {
    background: var(--tp-primary-weak);
    color: var(--tp-primary);
    font-size: 28px;
    border: 2px solid var(--tp-border);
  }

  .avatar-tip {
    font-size: 12px;
    color: var(--tp-text-secondary);
    max-width: 160px;
    text-align: center;
    line-height: 1.5;
  }
}

.profile-form-body {
  flex: 1;
  min-width: 0;
  padding-top: 4px;
}

.profile-form-narrow {
  max-width: 480px;
}
</style>

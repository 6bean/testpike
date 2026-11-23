<template>
  <!-- tp-page-root-exempt: login screen renders its own centered full-viewport
       layout (no .layout-main chrome); the edge-gap rule does not apply. -->
  <div class="login-bg">
    <div class="login-card">
      <div class="login-brand">
        <el-icon :size="38" color="#38bdf8"><Aim /></el-icon>
        <div class="brand-title">{{ t('common.appName') }}</div>
        <div class="brand-subtitle">{{ t('login.subtitle') }}</div>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="onSubmit" scroll-to-error>
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            :placeholder="t('login.usernamePlaceholder')"
            :prefix-icon="User"
            autocomplete="username"
            autofocus
            data-test="login-username"
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            :placeholder="t('login.passwordPlaceholder')"
            :prefix-icon="Lock"
            show-password
            autocomplete="current-password"
            data-test="login-password"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            class="login-btn"
            :loading="loading"
            data-test="login-submit"
            @click="onSubmit"
          >
            {{ loading ? t('login.submitting') : t('login.submit') }}
          </el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

const rules: FormRules = {
  username: [{ required: true, message: t('login.usernameRequired'), trigger: 'blur' }],
  password: [{ required: true, message: t('login.passwordRequired'), trigger: 'blur' }],
}

async function onSubmit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await authStore.login(form.username.trim(), form.password)
    ElMessage.success(t('login.success'))
    const redirect = (route.query.redirect as string) || '/dashboard'
    router.push(redirect)
  } catch (e) {
    const msg = e instanceof Error ? e.message : t('login.fail')
    ElMessage.error(msg)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
.login-card {
  width: 400px;
  max-width: calc(100vw - 40px);
  background: var(--tp-bg-card);
  border: 1px solid var(--tp-border);
  border-radius: 16px;
  box-shadow: var(--tp-shadow);
  padding: 40px 36px 28px;
}

.login-brand {
  text-align: center;
  margin-bottom: 28px;
}

.brand-title {
  font-size: 24px;
  font-weight: 600;
  margin-top: 10px;
  background: linear-gradient(90deg, #38bdf8, #818cf8);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.brand-subtitle {
  font-size: 13px;
  color: var(--tp-text-secondary);
  margin-top: 6px;
}

.login-btn {
  width: 100%;
}
</style>

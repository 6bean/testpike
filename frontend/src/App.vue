<template>
  
  <el-config-provider :locale="elLocale">
    <router-view />
  </el-config-provider>
</template>

<script setup lang="ts">
import { elLocale } from './utils/i18n'
</script>

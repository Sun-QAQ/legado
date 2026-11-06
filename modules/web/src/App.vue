<template>
  <web-pairing>
    <web-layout>
      <router-view v-slot="{ Component }">
        <keep-alive include="BookExplore">
          <component :is="Component" :key="$route.path" />
        </keep-alive>
      </router-view>
    </web-layout>
  </web-pairing>
</template>
<script setup lang="ts">
import '@/assets/web-layout.css'
import WebPairing from '@/components/WebPairing.vue'
</script>

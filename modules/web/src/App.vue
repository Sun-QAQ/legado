<template>
  <web-layout><router-view :key="$route.path" /></web-layout>
</template>
<script setup lang="ts">
import '@/assets/web-layout.css'
</script>

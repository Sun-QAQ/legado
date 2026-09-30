<template>
  <div class="read-settings">
    <section>
      <h3>阅读背景</h3>
      <div class="theme-options">
        <button
          v-for="(color, index) in themeColors"
          :key="index"
          :style="color"
          :class="{ selected: theme === index }"
          :aria-label="`选择${['纸张', '暖黄', '护眼', '清蓝', '浅粉', '灰色', '夜间'][index]}背景`"
          :aria-pressed="theme === index"
          @click="setTheme(index)"
        >
          {{ ['纸张', '暖黄', '护眼', '清蓝', '浅粉', '灰色', '夜间'][index] }}
        </button>
      </div>
    </section>
    <section>
      <h3>正文字体</h3>
      <div class="inline-actions">
        <el-button
          v-for="(font, index) in fonts"
          :key="font"
          :type="selectedFont === index ? 'primary' : 'default'"
          @click="setFont(index)"
          >{{ font }}</el-button
        >
      </div>
      <label for="custom-font-name">自定义字体名称</label
      ><el-input
        id="custom-font-name"
        v-model="customFontName"
        placeholder="本机字体或网络字体名称"
      />
      <div class="inline-actions font-actions">
        <el-button @click="setCustomFont">使用本机字体</el-button
        ><el-button @click="loadFontFromURL">下载网络字体</el-button>
      </div>
    </section>
    <section class="range-settings">
      <label
        >字号 <output>{{ fontSize }} px</output
        ><el-slider
          v-model="store.config.fontSize"
          :min="12"
          :max="48"
          :step="2"
          aria-label="字体大小" /></label
      ><label
        >行距 <output>{{ spacing.line.toFixed(1) }}</output
        ><el-slider
          v-model="store.config.spacing.line"
          :min="0"
          :max="2"
          :step="0.1"
          aria-label="行距" /></label
      ><label
        >段距 <output>{{ spacing.paragraph.toFixed(1) }}</output
        ><el-slider
          v-model="store.config.spacing.paragraph"
          :min="0"
          :max="3"
          :step="0.1"
          aria-label="段距" /></label
      ><label
        >字距 <output>{{ spacing.letter.toFixed(2) }}</output
        ><el-slider
          v-model="store.config.spacing.letter"
          :min="0"
          :max="1"
          :step="0.01"
          aria-label="字距" /></label
      ><label v-if="!store.miniInterface"
        >页面宽度 <output>{{ readWidth }} px</output
        ><el-slider
          v-model="store.config.readWidth"
          :min="640"
          :max="Math.max(640, Math.floor((windowWidth - 136) / 160) * 160)"
          :step="160"
          aria-label="页面宽度" /></label
      ><label
        >翻页速度 <output>{{ jumpDuration }} ms</output
        ><el-slider
          v-model="store.config.jumpDuration"
          :min="0"
          :max="2000"
          :step="100"
          aria-label="翻页速度"
      /></label>
      <div class="setting-toggle">
        <span>无限加载</span
        ><el-switch
          v-model="store.config.infiniteLoading"
          aria-label="无限加载"
        />
      </div>
    </section>
  </div>
</template>
<script setup lang="ts">
import '../assets/fonts/popfont.css'
import '../assets/fonts/iconfont.css'
import API from '@api'
import { useWindowSize } from '@vueuse/core'
const { width: windowWidth } = useWindowSize()
import { useDebounceFn } from '@vueuse/shared'

const store = useBookStore()
const saveConfigDebounce = useDebounceFn(
  () => API.saveReadConfig(store.config),
  500,
)
//阅读界面设置改变时保存同步配置
watch(
  () => store.config,
  () => {
    saveConfigDebounce()
  },
  {
    deep: 2, //深度为2
  },
)

//主题颜色
const theme = computed(() => store.theme)

const themeColors = [
  {
    background: 'rgba(250, 245, 235, 0.8)',
  },
  {
    background: 'rgba(245, 234, 204, 0.8)',
  },
  {
    background: 'rgba(230, 242, 230, 0.8)',
  },
  {
    background: 'rgba(228, 241, 245, 0.8)',
  },
  {
    background: 'rgba(245, 228, 228, 0.8)',
  },
  {
    background: 'rgba(224, 224, 224, 0.8)',
  },
  {
    background: 'rgba(0, 0, 0, 0.5)',
  },
]

const setTheme = (theme: number) => {
  store.config.theme = theme
}

//预置字体
const fonts = ref(['雅黑', '宋体', '楷书'])
const setFont = (font: number) => {
  store.config.font = font
}
const selectedFont = computed(() => {
  return store.config.font
})
//自定义字体
const customFontName = ref(store.config.customFontName)
const customFontSavePopVisible = ref(false)
const setCustomFont = () => {
  customFontSavePopVisible.value = false
  store.config.font = -1
  store.config.customFontName = customFontName.value
}
// 加载网络字体
const loadFontFromURL = () => {
  customFontSavePopVisible.value = false
  ElMessageBox.prompt('请输入 字体网络链接', '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputPattern: /^https?:.+$/,
    inputErrorMessage: 'url 形式不正确',
    beforeClose: (action, instance, done) => {
      if (action === 'confirm') {
        instance.confirmButtonLoading = true
        instance.confirmButtonText = '下载中……'
        // instance.inputValue
        const url = instance.inputValue
        if (typeof FontFace !== 'function') {
          ElMessage.error('浏览器不支持FontFace')
          return done()
        }
        const fontface = new FontFace(customFontName.value, `url("${url}")`)
        document.fonts.add(fontface)
        fontface
          .load()
          //API.getBookShelf()
          .then(function () {
            instance.confirmButtonLoading = false
            ElMessage.info('字体加载成功！')
            setCustomFont()
            done()
          })
          .catch(function (error) {
            instance.confirmButtonLoading = false
            instance.confirmButtonText = '确定'
            ElMessage.error('下载失败，请检查您输入的 url')
            throw error
          })
      } else {
        done()
      }
    },
  })
}

//字体大小
const fontSize = computed(() => {
  return store.config.fontSize
})

const spacing = computed(() => {
  return store.config.spacing
})

const readWidth = computed(() => {
  return store.config.readWidth
})

const jumpDuration = computed(() => {
  return store.config.jumpDuration
})
</script>

<style scoped>
.read-settings {
  color: var(--web-text);
  min-width: 0;
}
.read-settings section {
  padding: 8px 0 22px;
  border-bottom: 1px solid var(--web-line);
  margin-bottom: 18px;
}
.read-settings h3 {
  font-size: 14px;
  margin: 0 0 16px;
}
.theme-options {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.theme-options button {
  border: 2px solid transparent;
  border-radius: 8px;
  padding: 9px 12px;
  color: #37332e;
  cursor: pointer;
  font-size: 12px;
}
.theme-options button:last-child {
  color: #eee;
}
.theme-options .selected {
  border-color: var(--web-primary);
}
.read-settings label {
  display: block;
  color: var(--web-muted);
  font-size: 12px;
  margin: 18px 0 8px;
}
.font-actions {
  margin-top: 12px;
}
.range-settings output {
  float: right;
  color: var(--web-text);
}
.range-settings :deep(.el-slider) {
  padding: 0 12px;
}
.setting-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 22px;
}
</style>

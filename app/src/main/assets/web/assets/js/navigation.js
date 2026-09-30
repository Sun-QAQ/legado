(() => {
  const form = document.getElementById("book-search");
  const input = document.getElementById("search-key");
  const status = document.getElementById("connection-status");
  const root = document.documentElement;
  const systemTheme = window.matchMedia("(prefers-color-scheme: dark)");
  root.classList.toggle("dark", systemTheme.matches);

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const key = input.value.trim();
    if (!key) {
      input.setCustomValidity("请输入书名或作者");
      input.reportValidity();
      return;
    }
    location.href = "vue/index.html#/search?" + new URLSearchParams({ q: key });
  });
  input.addEventListener("input", () => input.setCustomValidity(""));

  let pending = false;
  const syncTheme = async () => {
    if (document.hidden || pending) return;
    pending = true;
    try {
      const response = await fetch("getReadConfig", {
        signal: AbortSignal.timeout(3000),
      });
      if (!response.ok) throw new Error("服务连接失败");
      const result = await response.json();
      if (!result.isSuccess) throw new Error("配置读取失败");
      const config = JSON.parse(result.data);
      const night =
        typeof config.theme === "number"
          ? config.theme === 6
          : systemTheme.matches;
      root.classList.toggle("dark", night);
      const accent = config.appAccentColor;
      if (typeof accent === "string" && /^#[\da-f]{6}$/i.test(accent)) {
        root.style.setProperty("--accent", accent);
        const channels = [1, 3, 5].map((index) =>
          parseInt(accent.slice(index, index + 2), 16),
        );
        const background = night ? [36, 38, 44] : [255, 255, 255];
        const tint = channels.map((channel, index) =>
          Math.round(channel * 0.1 + background[index] * 0.9),
        );
        root.style.setProperty("--accent-soft", `rgb(${tint.join(",")})`);
        const luminance = channels.reduce((total, channel, index) => {
          const value = channel / 255;
          return (
            total +
            (value <= 0.04045
              ? value / 12.92
              : ((value + 0.055) / 1.055) ** 2.4) *
              [0.2126, 0.7152, 0.0722][index]
          );
        }, 0);
        root.style.setProperty(
          "--on-accent",
          luminance > 0.179 ? "#000000" : "#ffffff",
        );
      }
      status.textContent = "让阅读回到内容本身";
    } catch {
      status.textContent = "请确认阅读 App 的 Web 服务已开启";
    } finally {
      pending = false;
    }
  };
  void syncTheme();
  window.addEventListener("focus", syncTheme);
  document.addEventListener("visibilitychange", syncTheme);
  setInterval(syncTheme, 15000);
})();

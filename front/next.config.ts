import type { NextConfig } from "next";

// 读取服务器配置
const serverConfig = require('./server.config.js');

const nextConfig: NextConfig = {
  env: {
    APP_NAME: serverConfig.app.name,
    APP_VERSION: serverConfig.app.version,
  },

  // 静态导出配置
  output: 'export',
  // 禁用图片优化（静态导出不支持）
  images: {
    unoptimized: true,
  },
};

export default nextConfig;

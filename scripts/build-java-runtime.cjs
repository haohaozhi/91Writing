const {
  copyFileSync,
  cpSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readdirSync,
  realpathSync,
  rmSync,
  statSync,
} = require("node:fs");
const { join } = require("node:path");
const { spawnSync } = require("node:child_process");

const cliValue = (name) => {
  const prefix = `--${name}=`;
  const inline = process.argv.find((arg) => arg.startsWith(prefix));
  if (inline) {
    return inline.slice(prefix.length);
  }
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 ? process.argv[index + 1] : "";
};

const projectRoot = join(__dirname, "..");
const runtimeDir = join(projectRoot, "backend", "target", "runtime");
const vendorJdkRoot = join(projectRoot, "vendor", "jdks");
const runtimeId =
  cliValue("target") ||
  process.env.DREAM_WRITER_JAVA_RUNTIME_ID ||
  `${process.platform}-${process.arch}`;
const vendorRuntimeDir =
  cliValue("runtime-dir") ||
  process.env.DREAM_WRITER_JAVA_RUNTIME_DIR ||
  join(vendorJdkRoot, runtimeId);
const targetPlatform = runtimeId.split("-")[0] || process.platform;
const executable = process.platform === "win32" ? "jlink.exe" : "jlink";
const javaExecutable = targetPlatform === "win32" ? "java.exe" : "java";
const javaHomeJlink = process.env.JAVA_HOME
  ? join(process.env.JAVA_HOME, "bin", executable)
  : null;
const jlink =
  javaHomeJlink && existsSync(javaHomeJlink) ? javaHomeJlink : executable;
const installVendor = process.argv.includes("--install-vendor");
const forceJlink = installVendor || process.argv.includes("--force-jlink");

const modules = [
  "java.base",
  "java.compiler",
  "java.desktop",
  "java.instrument",
  "java.logging",
  "java.management",
  "java.naming",
  "java.net.http",
  "java.prefs",
  "java.security.jgss",
  "java.sql",
  "java.transaction.xa",
  "java.xml",
  "jdk.crypto.ec",
  "jdk.unsupported",
  "jdk.zipfs",
];

const javaHomeCandidates = (directory) => [
  directory,
  join(directory, "Contents", "Home"),
];

const resolveJavaHome = (directory) =>
  javaHomeCandidates(directory).find((candidate) =>
    existsSync(join(candidate, "bin", javaExecutable)),
  ) || "";

const copyRuntime = (from, to) => {
  const javaHome = resolveJavaHome(from);
  if (!javaHome) {
    throw new Error(`Java runtime 不完整，缺少 bin/${javaExecutable}: ${from}`);
  }
  rmSync(to, { recursive: true, force: true });
  mkdirSync(join(to, ".."), { recursive: true });
  cpSync(javaHome, to, {
    recursive: true,
    dereference: true,
    force: true,
  });
  materializeSymlinks(to);
};

const materializeSymlinks = (directory) => {
  for (const entryName of readdirSync(directory)) {
    const entryPath = join(directory, entryName);
    const entryStat = lstatSync(entryPath);
    if (entryStat.isSymbolicLink()) {
      const realPath = realpathSync(entryPath);
      const realStat = statSync(realPath);
      rmSync(entryPath, { recursive: true, force: true });
      if (realStat.isDirectory()) {
        cpSync(realPath, entryPath, {
          recursive: true,
          dereference: true,
          force: true,
        });
        materializeSymlinks(entryPath);
      } else {
        copyFileSync(realPath, entryPath);
      }
      continue;
    }

    if (entryStat.isDirectory()) {
      materializeSymlinks(entryPath);
    }
  }
};

if (!forceJlink && resolveJavaHome(vendorRuntimeDir)) {
  console.log(`使用项目内 Java runtime/JDK: ${vendorRuntimeDir}`);
  copyRuntime(vendorRuntimeDir, runtimeDir);
  process.exit(0);
}

rmSync(runtimeDir, { recursive: true, force: true });

const result = spawnSync(
  jlink,
  [
    "--strip-debug",
    "--no-header-files",
    "--no-man-pages",
    "--compress=zip-6",
    "--add-modules",
    modules.join(","),
    "--output",
    runtimeDir,
  ],
  {
    stdio: "inherit",
    shell: process.platform === "win32",
  },
);

if (result.error) {
  console.error(result.error.message);
  process.exit(1);
}

if ((result.status ?? 0) !== 0) {
  process.exit(result.status ?? 1);
}

if (installVendor) {
  console.log(`写入项目内 Java runtime/JDK 目录: ${vendorRuntimeDir}`);
  copyRuntime(runtimeDir, vendorRuntimeDir);
}

process.exit(0);

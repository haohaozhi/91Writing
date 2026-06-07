const {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  statSync,
  writeFileSync,
} = require("node:fs");
const { extname, join, relative } = require("node:path");
const { spawnSync } = require("node:child_process");
const { createHash } = require("node:crypto");

const projectRoot = join(__dirname, "..");
const jdkRoot = join(projectRoot, "vendor", "jdks");
const releaseDir = join(projectRoot, "release");
const packageStartedAtMs = Date.now();

const targetMap = {
  "darwin-arm64": ["--mac", "--arm64"],
  "darwin-x64": ["--mac", "--x64"],
  "win32-x64": ["--win", "--x64"],
  "win32-arm64": ["--win", "--arm64"],
  "linux-x64": ["--linux", "--x64"],
  "linux-arm64": ["--linux", "--arm64"],
};

const cliValue = (name) => {
  const prefix = `--${name}=`;
  const inline = process.argv.find((arg) => arg.startsWith(prefix));
  if (inline) {
    return inline.slice(prefix.length);
  }
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 ? process.argv[index + 1] : "";
};

const hasFlag = (name) => process.argv.includes(`--${name}`);

const requestedTargets = (
  cliValue("targets") ||
  process.env.DREAM_WRITER_PACKAGE_TARGETS ||
  Object.keys(targetMap).join(",")
)
  .split(",")
  .map((target) => target.trim())
  .filter(Boolean);

const targetJavaExecutable = (target) =>
  target.startsWith("win32-") ? "java.exe" : "java";

const targetRuntimeHomes = (target) => {
  const directory = join(jdkRoot, target);
  return [directory, join(directory, "Contents", "Home")];
};

const targetRuntimeStatus = (target) => {
  const executable = targetJavaExecutable(target);
  const checkedHomes = targetRuntimeHomes(target);
  const javaHome =
    checkedHomes.find((directory) =>
      existsSync(join(directory, "bin", executable)),
    ) || "";

  return {
    target,
    runtimePath: runtimePathHint(target),
    javaExecutable: executable,
    ready: Boolean(javaHome),
    javaHome,
    checkedHomes,
  };
};

const hasTargetRuntime = (target) => targetRuntimeStatus(target).ready;

const runtimePathHint = (target) => join(jdkRoot, target);

const targetOutputHint = (target) => {
  if (target === "darwin-arm64") {
    return "mac-arm64";
  }
  if (target === "darwin-x64") {
    return "mac";
  }
  if (target === "win32-arm64") {
    return "win-arm64-unpacked";
  }
  if (target === "win32-x64") {
    return "win-unpacked";
  }
  if (target === "linux-arm64") {
    return "linux-arm64-unpacked";
  }
  if (target === "linux-x64") {
    return "linux-unpacked";
  }
  return target;
};

const releaseArtifactExtensions = new Set([
  ".AppImage",
  ".blockmap",
  ".deb",
  ".dmg",
  ".exe",
  ".yml",
  ".zip",
]);

const selectedTargets = hasFlag("available")
  ? requestedTargets.filter(
      (target) => targetMap[target] && hasTargetRuntime(target),
    )
  : requestedTargets;

const missingTargets = requestedTargets.filter(
  (target) => targetMap[target] && !hasTargetRuntime(target),
);

const run = (command, args, options = {}) => {
  const result = spawnSync(command, args, {
    cwd: projectRoot,
    env: {
      ...process.env,
      ...(options.env || {}),
    },
    stdio: "inherit",
    shell: process.platform === "win32",
  });

  if (result.error) {
    console.error(result.error.message);
    process.exit(1);
  }
  if ((result.status ?? 0) !== 0) {
    process.exit(result.status ?? 1);
  }
};

const printTargetSummary = () => {
  console.log("打包目标检查:");
  for (const target of Object.keys(targetMap)) {
    const status = targetRuntimeStatus(target);
    const readyText = status.ready ? "ready" : "missing";
    console.log(`- ${target}: ${readyText} (${status.runtimePath})`);
  }
};

const directorySize = (directory) => {
  if (!existsSync(directory)) {
    return 0;
  }
  const stats = statSync(directory);
  if (!stats.isDirectory()) {
    return stats.size;
  }
  return readdirSync(directory).reduce(
    (size, name) => size + directorySize(join(directory, name)),
    0,
  );
};

const fileSha256 = (filePath) =>
  createHash("sha256").update(readFileSync(filePath)).digest("hex");

const isReleaseArtifactFile = (fileName) => {
  if (fileName === "package-manifest.json") {
    return false;
  }
  if (fileName.startsWith("latest") && fileName.endsWith(".yml")) {
    return true;
  }
  const extension = extname(fileName);
  return extension !== ".yml" && releaseArtifactExtensions.has(extension);
};

const topLevelReleaseFiles = (sinceMs) => {
  if (!existsSync(releaseDir)) {
    return [];
  }

  return readdirSync(releaseDir)
    .filter(isReleaseArtifactFile)
    .map((fileName) => {
      const filePath = join(releaseDir, fileName);
      const stats = statSync(filePath);
      return {
        fileName,
        filePath,
        stats,
      };
    })
    .filter(({ stats }) => stats.isFile() && stats.mtimeMs >= sinceMs - 5000)
    .sort((left, right) => left.fileName.localeCompare(right.fileName))
    .map(({ fileName, filePath, stats }) => ({
      path: fileName,
      size: stats.size,
      modifiedAt: stats.mtime.toISOString(),
      sha256: fileSha256(filePath),
    }));
};

const targetArtifactSummary = (target) => {
  const outputDirectory = join(releaseDir, targetOutputHint(target));
  const resourceDirectories = [
    join(outputDirectory, "DreamWriter.app", "Contents", "Resources"),
    join(outputDirectory, "resources"),
  ];
  const hasBundledFile = (...segments) =>
    resourceDirectories.some((directory) =>
      existsSync(join(directory, ...segments)),
    );
  const outputExists = existsSync(outputDirectory);

  return {
    target,
    outputPath: relative(releaseDir, outputDirectory).split("\\").join("/"),
    outputExists,
    outputSize: outputExists ? directorySize(outputDirectory) : 0,
    runtimeBundled: hasBundledFile(
      "runtime",
      "bin",
      targetJavaExecutable(target),
    ),
    backendBundled: hasBundledFile("backend", "dream-writer-backend.jar"),
  };
};

const verifyPackagedArtifacts = (artifacts) => {
  const failedArtifacts = artifacts.filter(
    (artifact) =>
      !artifact.outputExists ||
      !artifact.runtimeBundled ||
      !artifact.backendBundled,
  );
  if (failedArtifacts.length === 0) {
    return;
  }

  console.error("打包产物校验失败:");
  for (const artifact of failedArtifacts) {
    console.error(
      `- ${artifact.target}: output=${artifact.outputExists}, runtime=${artifact.runtimeBundled}, backend=${artifact.backendBundled}`,
    );
  }
  process.exit(1);
};

const writePackageManifest = () => {
  mkdirSync(releaseDir, { recursive: true });
  const artifacts = selectedTargets.map(targetArtifactSummary);
  const manifest = {
    generatedAt: new Date().toISOString(),
    requestedTargets,
    runtimeTargets: Object.keys(targetMap).map(targetRuntimeStatus),
    packagedTargets: selectedTargets,
    skippedTargets: requestedTargets.filter(
      (target) => !selectedTargets.includes(target),
    ),
    artifacts,
    releaseFiles: topLevelReleaseFiles(packageStartedAtMs),
  };
  writeFileSync(
    join(releaseDir, "package-manifest.json"),
    `${JSON.stringify(manifest, null, 2)}\n`,
  );
  return artifacts;
};

for (const target of requestedTargets) {
  if (!targetMap[target]) {
    console.error(`不支持的目标平台: ${target}`);
    console.error(`可用目标: ${Object.keys(targetMap).join(", ")}`);
    process.exit(1);
  }
}

if (hasFlag("check")) {
  printTargetSummary();
  if (!hasFlag("available") && missingTargets.length > 0) {
    process.exit(1);
  }
  process.exit(0);
}

if (hasFlag("available") && missingTargets.length > 0) {
  console.warn(`跳过缺少 JDK/runtime 的目标: ${missingTargets.join(", ")}`);
}

if (selectedTargets.length === 0) {
  console.error(
    "没有可打包目标。请先安装目标平台 JDK/runtime 到 vendor/jdks/<target>。",
  );
  printTargetSummary();
  process.exit(1);
}

if (!hasFlag("available") && missingTargets.length > 0) {
  console.error(`缺少目标平台 JDK/runtime: ${missingTargets.join(", ")}`);
  missingTargets.forEach((target) =>
    console.error(`- ${target}: ${runtimePathHint(target)}`),
  );
  console.error("可使用 --available 只打包当前已安装 runtime 的目标。");
  process.exit(1);
}

if (!hasFlag("skip-build")) {
  run("pnpm", ["build"]);
  run("pnpm", ["backend:build"]);
}

for (const target of selectedTargets) {
  console.log(`\n=== 打包目标: ${target} ===`);
  run("node", ["scripts/build-java-runtime.cjs", "--target", target]);
  run(
    "pnpm",
    [
      "exec",
      "electron-builder",
      ...(hasFlag("dir") ? ["--dir"] : []),
      ...targetMap[target],
      "--publish",
      "never",
    ],
    {
      env: {
        DREAM_WRITER_JAVA_RUNTIME_ID: target,
      },
    },
  );
}

const artifacts = writePackageManifest();
verifyPackagedArtifacts(artifacts);
console.log("\n全部目标打包完成。");

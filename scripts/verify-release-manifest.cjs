const { existsSync, readFileSync, statSync } = require("node:fs");
const { join } = require("node:path");
const { createHash } = require("node:crypto");

const projectRoot = join(__dirname, "..");
const releaseDir = join(projectRoot, "release");
const manifestPath = join(releaseDir, "package-manifest.json");

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

const expectedTargets = cliValue("targets")
  .split(",")
  .map((target) => target.trim())
  .filter(Boolean);
const requireReleaseFiles = hasFlag("require-release-files");
const requireAllRequested = hasFlag("require-all-requested");

const failures = [];

const fail = (message) => {
  failures.push(message);
};

const readJson = (filePath) => {
  try {
    return JSON.parse(readFileSync(filePath, "utf8"));
  } catch (error) {
    fail(`无法读取 JSON: ${filePath}: ${error.message}`);
    return null;
  }
};

const fileSha256 = (filePath) =>
  createHash("sha256").update(readFileSync(filePath)).digest("hex");

if (!existsSync(manifestPath)) {
  fail(`缺少 release manifest: ${manifestPath}`);
}

const manifest = existsSync(manifestPath) ? readJson(manifestPath) : null;

if (manifest) {
  const packagedTargets = Array.isArray(manifest.packagedTargets)
    ? manifest.packagedTargets
    : [];
  const requestedTargets = Array.isArray(manifest.requestedTargets)
    ? manifest.requestedTargets
    : [];
  const artifacts = Array.isArray(manifest.artifacts) ? manifest.artifacts : [];
  const releaseFiles = Array.isArray(manifest.releaseFiles)
    ? manifest.releaseFiles
    : [];

  for (const target of expectedTargets) {
    if (!packagedTargets.includes(target)) {
      fail(`manifest packagedTargets 缺少目标: ${target}`);
    }
  }

  if (requireAllRequested) {
    const skippedTargets = Array.isArray(manifest.skippedTargets)
      ? manifest.skippedTargets
      : [];
    if (skippedTargets.length > 0) {
      fail(`manifest skippedTargets 非空: ${skippedTargets.join(", ")}`);
    }
    for (const target of requestedTargets) {
      if (!packagedTargets.includes(target)) {
        fail(`请求目标未被打包: ${target}`);
      }
    }
  }

  for (const artifact of artifacts) {
    const label = artifact?.target || "<unknown>";
    if (!artifact?.outputExists) {
      fail(`${label}: outputExists=false`);
    }
    if (!artifact?.runtimeBundled) {
      fail(`${label}: runtimeBundled=false`);
    }
    if (!artifact?.backendBundled) {
      fail(`${label}: backendBundled=false`);
    }
    if (
      !Number.isFinite(Number(artifact?.outputSize)) ||
      artifact.outputSize <= 0
    ) {
      fail(`${label}: outputSize 无效`);
    }
  }

  if (requireReleaseFiles && releaseFiles.length === 0) {
    fail("manifest releaseFiles 为空");
  }

  for (const file of releaseFiles) {
    const relativePath = file?.path || "";
    const filePath = join(releaseDir, relativePath);
    if (!relativePath || relativePath.includes("..")) {
      fail(`releaseFiles path 无效: ${relativePath}`);
      continue;
    }
    if (!existsSync(filePath)) {
      fail(`releaseFiles 文件不存在: ${relativePath}`);
      continue;
    }
    const stats = statSync(filePath);
    if (!stats.isFile()) {
      fail(`releaseFiles 不是文件: ${relativePath}`);
      continue;
    }
    if (stats.size !== file.size) {
      fail(`releaseFiles size 不匹配: ${relativePath}`);
    }
    const actualSha256 = fileSha256(filePath);
    if (actualSha256 !== file.sha256) {
      fail(`releaseFiles sha256 不匹配: ${relativePath}`);
    }
  }
}

if (failures.length > 0) {
  console.error("release manifest 校验失败:");
  for (const message of failures) {
    console.error(`- ${message}`);
  }
  process.exit(1);
}

console.log("release manifest 校验通过。");

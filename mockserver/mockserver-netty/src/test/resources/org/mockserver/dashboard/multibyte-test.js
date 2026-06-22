// regression fixture for issue #2347 — contains multi-byte UTF-8 characters
// so that the UTF-8 byte length differs from both the char count and the
// platform-default (e.g. windows-1252) encoded length: café © € Ω ≈ ç √ ∫ ★
const dashboardGreeting = "Привет, 世界 — café ©";

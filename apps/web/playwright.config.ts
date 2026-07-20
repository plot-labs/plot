import { loadBrowserCertificationConfig } from "./e2e/support/certification-manifest";
import { createCertificationPlaywrightConfig } from "./e2e/support/playwright-certification-config";

export default createCertificationPlaywrightConfig(loadBrowserCertificationConfig());

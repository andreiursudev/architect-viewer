export interface StackRun {
  scenario: string;
  run: string;
}

declare global {
  interface Window {
    /** Set by the test harness (StackBrowser.tag) before the page's own scripts run. */
    __STACK__?: StackRun;
    /** Set by startStackRecording: exports every finished span now. */
    __STACK_FLUSH__?: () => Promise<void>;
  }
}

/** The scenario and run the harness tagged this page with, or null when the page is not part of a recorded run. */
export function currentRun(): StackRun | null {
  const tagged = typeof window === 'undefined' ? undefined : window.__STACK__;
  if (typeof tagged?.scenario !== 'string' || typeof tagged.run !== 'string') return null;
  return { scenario: tagged.scenario, run: tagged.run };
}

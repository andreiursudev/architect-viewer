const MAX_NAME_CHARS = 80;

/**
 * Names what a click or submit was on: the data-testid of the nearest element (the target or an ancestor) that has
 * one; else the accessible name (aria-label, else trimmed text) of the nearest button, link or form, cut to 80
 * characters; else the target's tag name.
 */
export function describeTarget(target: EventTarget | null): string {
  const element = target instanceof Element ? target : target instanceof Node ? target.parentElement : null;
  if (!element) return '';

  const testId = element.closest('[data-testid]')?.getAttribute('data-testid');
  if (testId) return testId;

  const control = element.closest('button, a, form');
  const name = (control?.getAttribute('aria-label') || control?.textContent || '').replace(/\s+/g, ' ').trim();
  if (name) return name.slice(0, MAX_NAME_CHARS).trimEnd();

  return element.tagName.toLowerCase();
}

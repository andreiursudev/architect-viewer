import { afterEach, expect, it } from 'vitest';
import { describeTarget } from './describeTarget.js';

afterEach(() => {
  document.body.innerHTML = '';
});

function first(selector: string): Element {
  const element = document.querySelector(selector);
  if (!element) throw new Error(`no ${selector} in the test page`);
  return element;
}

it('describeTarget prefers the nearest data-testid', () => {
  document.body.innerHTML = `
    <form data-testid="order-form">
      <button data-testid="place-order"><span>Place order</span></button>
    </form>`;

  expect(describeTarget(first('span'))).toBe('place-order');
  expect(describeTarget(first('form'))).toBe('order-form');
});

it('describeTarget falls back to the button text, cut to 80 characters', () => {
  const long = 'Order '.repeat(20);
  document.body.innerHTML = `
    <button>
      <span>${long}</span>
    </button>
    <a aria-label="Back to the stock list" href="/"><img alt=""></a>`;

  expect(describeTarget(first('span'))).toBe(long.trim().slice(0, 80));
  expect(describeTarget(first('img'))).toBe('Back to the stock list');
});

it('describeTarget falls back to the tag name', () => {
  document.body.innerHTML = '<main><p>Nothing to name</p></main>';

  expect(describeTarget(first('p'))).toBe('p');
  expect(describeTarget(null)).toBe('');
});

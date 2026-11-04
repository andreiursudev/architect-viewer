import { expect, test } from '@playwright/test';

test('clicking a message shows its payload', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: /scenario/i }).click();
  await page.getByRole('option', { name: /^create-order(?!-)/ }).click();
  await page.getByLabel('Run').selectOption('run-1'); // the older run: orders-service alone, recorded without method events
  await page.getByRole('button', { name: 'Open sequence' }).click();

  const diagram = page.getByRole('group', { name: 'Sequence diagram' });
  await expect(diagram).toBeVisible();
  await expect(page.locator('.seq-head')).toHaveCount(3);
  await expect(diagram.getByRole('button')).toHaveCount(4);

  await diagram.getByRole('button', { name: 'request POST /orders' }).click();
  const details = page.getByRole('complementary', { name: 'Message details' });
  await expect(details).toContainText('customerId');
  await expect(details).toContainText('201');

  await diagram.getByRole('button', { name: /^request insert into orders/ }).click();
  await expect(details).toContainText('values (?, ?, ?, ?)');
  await expect(details.getByRole('table', { name: 'Parameters' })).toContainText('CREATED');
});

test('the sequence of a run survives a reload', async ({ page }) => {
  await page.goto('/sequence/run-1');
  await page.reload();
  await expect(page.getByRole('group', { name: 'Sequence diagram' })).toBeVisible();
  await expect(page.locator('.seq-head')).toHaveCount(3);
});

test('an unknown run says so and leads back to the board', async ({ page }) => {
  await page.goto('/sequence/no-such-run');
  await expect(page.getByText(/could not load the run/i)).toBeVisible();
  await page.getByRole('link', { name: /board/i }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.locator('.react-flow__node')).toHaveCount(9);
});

test('a large diagram is dragged around from an empty spot, and a message still opens', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 600 });
  await page.goto('/sequence/run-7'); // create-order across kafka: eight lifelines, wider and taller than this window
  const diagram = page.getByRole('group', { name: 'Sequence diagram' });
  await expect(diagram).toBeVisible();
  await page.getByRole('button', { name: 'Expand all' }).click();

  const pane = page.locator('.sequence-scroll');
  const box = (await pane.boundingBox())!;
  // the margin left of the first lifeline holds nothing to click
  const x = box.x + 40;
  const y = box.y + box.height - 40;
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x - 150, y - 120, { steps: 5 });
  await page.mouse.up();
  await expect.poll(() => pane.evaluate((el) => [el.scrollLeft, el.scrollTop])).toEqual([150, 120]);

  await diagram.getByRole('button', { name: 'request consume order-placed' }).click();
  await expect(page.getByRole('complementary', { name: 'Message details' })).toContainText('Produced by');
});

import { expect, test, type Page } from '@playwright/test';

async function pickCreateOrder(page: Page) {
  await page.getByRole('button', { name: /scenario/i }).click();
  await page.getByRole('option', { name: /create-order/ }).click();
}

test('board shows components and highlights create-order', async ({ page }) => {
  await page.goto('/');
  const nodes = page.locator('.react-flow__node');
  await expect(nodes).toHaveCount(3);
  for (const name of ['Orders service', 'e2e-client', 'postgresql-orders']) {
    await expect(nodes.filter({ hasText: name })).toBeVisible();
  }
  await expect(page.locator('.path-edge')).toHaveCount(0);

  await pickCreateOrder(page);

  await expect(page.locator('.react-flow__edge-path.path-edge')).toHaveCount(2);
  await expect(page.locator('.component-node.on-path')).toHaveCount(3);
});

test('dragging a node persists its position', async ({ page }) => {
  await page.goto('/');
  const node = page.locator('.react-flow__node').filter({ hasText: 'Orders service' });
  await expect(node).toBeVisible();
  const before = (await node.boundingBox())!;

  const saved = page.waitForResponse((r) => r.url().endsWith('/api/boards/primary/layout') && r.request().method() === 'PUT');
  await page.mouse.move(before.x + before.width / 2, before.y + before.height / 2);
  await page.mouse.down();
  await page.mouse.move(before.x + before.width / 2 + 60, before.y + before.height / 2 + 90, { steps: 10 });
  await page.mouse.up();
  const dragged = (await node.boundingBox())!;
  expect(dragged.x - before.x).toBeGreaterThan(40); // it really moved
  await saved;

  await page.reload();
  const after = (await page.locator('.react-flow__node').filter({ hasText: 'Orders service' }).boundingBox())!;
  expect(Math.abs(after.x - dragged.x)).toBeLessThanOrEqual(2);
  expect(Math.abs(after.y - dragged.y)).toBeLessThanOrEqual(2);
});

test('a drag is saved even when the user leaves the board right away', async ({ page }) => {
  await page.goto('/');
  await pickCreateOrder(page);
  const node = page.locator('.react-flow__node').filter({ hasText: 'Orders service' });
  const before = (await node.boundingBox())!;
  await page.mouse.move(before.x + before.width / 2, before.y + before.height / 2);
  await page.mouse.down();
  await page.mouse.move(before.x + before.width / 2 - 40, before.y + before.height / 2 + 50, { steps: 8 });
  await page.mouse.up();
  const dragged = (await node.boundingBox())!;

  await page.getByRole('button', { name: 'Open sequence' }).click(); // well within the 500 ms debounce
  await expect(page).toHaveURL(/\/sequence\/run-1$/);
  await page.getByRole('link', { name: /board/i }).click();

  const after = (await page.locator('.react-flow__node').filter({ hasText: 'Orders service' }).boundingBox())!;
  expect(Math.abs(after.x - dragged.x)).toBeLessThanOrEqual(2);
  expect(Math.abs(after.y - dragged.y)).toBeLessThanOrEqual(2);

  await page.reload(); // and it really reached the backend
  const reloaded = (await page.locator('.react-flow__node').filter({ hasText: 'Orders service' }).boundingBox())!;
  expect(Math.abs(reloaded.x - dragged.x)).toBeLessThanOrEqual(2);
  expect(Math.abs(reloaded.y - dragged.y)).toBeLessThanOrEqual(2);
});

test('editing a note persists', async ({ page }) => {
  await page.goto('/');
  await page.locator('.react-flow__node').filter({ hasText: 'Orders service' }).click();
  const notes = page.getByRole('region', { name: 'Notes' });
  await expect(notes.getByText('Owns order placement.')).toBeVisible();

  await notes.getByRole('button', { name: 'Edit' }).click();
  await notes.getByRole('textbox', { name: 'Note' }).fill('# Orders service\n\nOwned by the checkout team.');
  await notes.getByRole('button', { name: 'Save' }).click();
  await expect(notes.getByText('Owned by the checkout team.')).toBeVisible();

  await page.reload();
  await page.locator('.react-flow__node').filter({ hasText: 'Orders service' }).click();
  await expect(page.getByRole('region', { name: 'Notes' }).getByText('Owned by the checkout team.')).toBeVisible();
});

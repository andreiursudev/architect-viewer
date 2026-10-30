import { expect, test, type Page } from '@playwright/test';

// run-5 is create-order across both services, with the methods inside them recorded
async function openRun(page: Page) {
  await page.goto('/sequence/run-5');
  await expect(page.getByRole('group', { name: 'Sequence diagram' })).toBeVisible();
}

const heads = (page: Page) => page.locator('.seq-head');
const diagram = (page: Page) => page.getByRole('group', { name: 'Sequence diagram' });

test('run opens collapsed', async ({ page }) => {
  await openRun(page);

  // the head's title is the component id; the reservation comes before the insert, so the inventory side is first
  await expect(heads(page)).toHaveCount(5);
  for (const [i, id] of ['e2e-client', 'orders-service', 'inventory-service', 'postgresql-inventory', 'postgresql-orders'].entries()) {
    await expect(heads(page).nth(i)).toHaveAttribute('title', id);
  }
  await expect(heads(page).filter({ hasText: 'Orders service' })).toHaveAttribute('title', 'orders-service'); // its display name

  await expect(heads(page).nth(1).getByRole('button', { name: '▸ 4 classes' })).toBeVisible();
  await expect(diagram(page).getByRole('button', { name: /^request reserve/ })).toHaveCount(0);
});

test('expanding orders-service shows its classes and a method call', async ({ page }) => {
  await openRun(page);

  await page.getByRole('button', { name: '▸ 4 classes' }).click();
  await expect(page.getByRole('button', { name: 'Collapse Orders service' })).toBeVisible();

  const classes = page.locator('.seq-head.is-class');
  await expect(classes).toHaveCount(4);
  for (const name of ['OrdersController', 'OrderService', 'InventoryClient', 'OrderRepository']) {
    await expect(classes.filter({ hasText: name })).toHaveCount(1);
  }
  await expect(classes.filter({ hasText: 'InventoryClient' })).toHaveAttribute('title', 'io.stackvibed.sandbox.orders.InventoryClient');

  await diagram(page).getByRole('button', { name: 'request reserve("sku-1", 2)' }).click();
  const details = page.getByRole('complementary', { name: 'Message details' });
  await expect(details).toContainText('InventoryClient');
  const quantity = details.getByRole('table', { name: 'Arguments' }).getByRole('row', { name: /quantity/ });
  await expect(quantity.getByRole('cell')).toHaveText('2');
});

test('collapse all returns to the collapsed diagram', async ({ page }) => {
  await openRun(page);

  await page.getByRole('button', { name: 'Expand all' }).click();
  await expect(page.locator('.seq-head.is-class')).toHaveCount(6); // 4 in orders-service, 2 in inventory-service
  await expect(diagram(page).getByRole('button', { name: 'request reserve("sku-1", 2)' }).first()).toBeVisible();

  await page.getByRole('button', { name: 'Collapse all' }).click();
  await expect(page.locator('.seq-head.is-class')).toHaveCount(0);
  await expect(heads(page)).toHaveCount(5);
  await expect(page.getByRole('button', { name: '▸ 4 classes' })).toBeVisible();
  await expect(diagram(page).getByRole('button', { name: /^request reserve/ })).toHaveCount(0);
});

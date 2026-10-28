import { expect, test } from '@playwright/test';

test('clicking a message shows its payload', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: /scenario/i }).click();
  await page.getByRole('option', { name: /^create-order(?!-)/ }).click();
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
  await expect(page.locator('.react-flow__node')).toHaveCount(5);
});

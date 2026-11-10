import { expect, test } from '@playwright/test';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const tempRoot = mkdtempSync(join(tmpdir(), 'stack-viewer-e2e-new-'));

// whatever the test reached, the shared backend's list ends as it began and the temp folder is gone
test.afterEach(async ({ request }) => {
  const listed = await request.get('/api/workspaces');
  const names = ((await listed.json()) as { workspaces: { name: string }[] }).workspaces.map((w) => w.name);
  if (names.includes('scratch')) await request.delete('/api/workspaces/scratch');
  rmSync(tempRoot, { recursive: true, force: true });
});

test('adds a workspace, switches to it and back, then removes it', async ({ page }) => {
  // a folder that does not exist yet: the viewer creates <folder>/stack/workspace.yaml
  const folder = join(tempRoot, 'scratch-project');

  await page.goto('/');
  await expect(page).toHaveURL(/\/w\/orders-demo\/$/);
  await expect(page.locator('.react-flow__node')).toHaveCount(9);

  const switcher = page.getByRole('button', { name: /Orders demo/ });
  await switcher.click();
  const initialCount = await page.getByRole('menuitemradio').count();
  await page.keyboard.press('Escape');
  await switcher.click();
  await page.getByRole('menuitem', { name: 'Add workspace…' }).click();
  const add = page.getByRole('dialog');
  await add.getByLabel('Folder', { exact: true }).fill(folder);
  await add.getByLabel('Name', { exact: true }).fill('scratch');
  await add.getByRole('button', { name: 'Add', exact: true }).click();

  await expect(page).toHaveURL(/\/w\/scratch\/$/);
  await expect(page.locator('.react-flow__node')).toHaveCount(0);

  await page.locator('.switcher-button').click();
  await page.getByRole('menuitemradio', { name: /Orders demo/ }).click();
  await expect(page).toHaveURL(/\/w\/orders-demo\/$/);
  await expect(page.locator('.react-flow__node')).toHaveCount(9);

  await switcher.click();
  await page.getByRole('menuitem', { name: 'Manage…' }).click();
  const manage = page.getByRole('dialog');
  const row = manage.getByRole('listitem').filter({ hasText: 'scratch' });
  await row.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(row.getByText('Remove scratch from the list? No files are deleted.')).toBeVisible();
  await row.getByRole('button', { name: 'Remove from list' }).click();
  await expect(manage.getByRole('listitem').filter({ hasText: 'scratch' })).toHaveCount(0);
  await manage.getByRole('button', { name: 'Close' }).click();

  await switcher.click();
  await expect(page.getByRole('menuitemradio')).toHaveCount(initialCount);
  await expect(page.getByRole('menuitemradio', { name: /scratch/ })).toHaveCount(0);
});

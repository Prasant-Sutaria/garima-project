import { test, expect } from '@playwright/test';

const stubBackend = 'http://127.0.0.1:3101';
// Unique per run so values never collide with earlier tests in the same stub backend.
const run = Date.now().toString(36);

async function fillRegistration(page, { name, mobile, email }) {
  await page.fill('#user_name', name);
  await page.fill('#age', '30');
  await page.getByRole('radio', { name: 'Female' }).check();
  await page.getByRole('radio', { name: 'Working professional' }).check();
  await page.getByRole('radio', { name: '6 months (free)' }).check();
  await page.fill('#mobile_number', mobile);
  await page.fill('#email', email);
  await page.locator('#indoor_activity .hobby-rows input').first().fill('Chess');
  await page.locator('#outdoor_activity .hobby-rows input').first().fill('Hiking');
  await page.getByLabel('By registering, you accept the terms and conditions.').check();
}

async function submit(page, details) {
  await page.goto('/');
  await fillRegistration(page, details);
  await page.locator('#submit-button').click();
}

async function registrationCount(request) {
  const response = await request.get(`${stubBackend}/count`);
  return (await response.json()).registrations;
}

test('the UI does not submit until the terms and conditions are accepted', async ({ page, request }) => {
  const before = await registrationCount(request);

  await page.goto('/');
  await fillRegistration(page, { name: 'Terms Example', mobile: '+14155550177', email: `terms-${run}@example.test` });
  await page.getByLabel('By registering, you accept the terms and conditions.').uncheck();
  await page.locator('#submit-button').click();

  await expect(page.locator('#consent-error')).toContainText('Accept the terms and conditions');
  await expect(page.getByLabel('By registering, you accept the terms and conditions.')).toBeFocused();
  expect(await registrationCount(request)).toBe(before);
});

test('the UI rejects a second registration with the same email', async ({ page, request }) => {
  const email = `duplicate-${run}@example.test`;
  const before = await registrationCount(request);

  await submit(page, { name: 'Alex Example', mobile: '+14155550101', email });
  await expect(page.locator('#registration-id')).toHaveText(/^P-[0-9A-F]{6}$/);

  // Same address with different casing is still the same person to the backend.
  await submit(page, { name: 'Alex Copy', mobile: '+14155550102', email: email.toUpperCase() });

  await expect(page.locator('#form-status')).toContainText('could not be completed');
  await expect(page.locator('#registration-id')).toHaveText('');
  await expect(page.locator('#submit-button')).toBeEnabled();

  expect(await registrationCount(request)).toBe(before + 1);
});

test('the UI rejects a second registration with the same mobile number', async ({ page, request }) => {
  const mobile = '+14155550199';
  const before = await registrationCount(request);

  await submit(page, { name: 'Sam Example', mobile, email: `first-${run}@example.test` });
  await expect(page.locator('#registration-id')).toHaveText(/^P-[0-9A-F]{6}$/);

  await submit(page, { name: 'Sam Other', mobile, email: `second-${run}@example.test` });

  await expect(page.locator('#form-status')).toContainText('could not be completed');
  await expect(page.locator('#registration-id')).toHaveText('');

  expect(await registrationCount(request)).toBe(before + 1);
});

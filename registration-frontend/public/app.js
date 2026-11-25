import { fields, validate, retryDelay, interpretResponse, unknownOutcome, joinHobbies } from './registration.js';

const form = document.querySelector('#registration-form');
const fieldset = document.querySelector('#registration-fields');
const button = document.querySelector('#submit-button');
const status = document.querySelector('#form-status');
const hobbyFields = {
  indoor_activity: { placeholder: 'e.g. Reading' },
  outdoor_activity: { placeholder: 'e.g. Walking' },
};
let submitting = false;
let blockedUntil = 0;
let countdown;
let outcomeUnknown = false;

function showStatus(message) {
  status.hidden = false;
  status.textContent = message;
  status.focus();
}

// Each field's error container and ARIA state live on the element whose id matches the field name.
function fieldRoot(name) {
  return document.getElementById(name);
}

function showFieldError(name, error) {
  fieldRoot(name).setAttribute('aria-invalid', String(Boolean(error)));
  document.getElementById(`${name}-error`).textContent = error || '';
}

function hobbyInputs(name) {
  return [...fieldRoot(name).querySelectorAll('.hobby-row input')];
}

function renumberHobbies(name) {
  hobbyInputs(name).forEach((input, index) => {
    input.setAttribute('aria-label', `${fields[name].label} hobby ${index + 1}`);
    input.placeholder = index === 0 ? hobbyFields[name].placeholder : '';
  });
}

function addHobby(name) {
  const rows = fieldRoot(name).querySelector('.hobby-rows');
  const row = document.createElement('div');
  row.className = 'hobby-row';
  const input = document.createElement('input');
  input.type = 'text';
  input.maxLength = fields[name].max;
  const remove = document.createElement('button');
  remove.type = 'button';
  remove.className = 'remove-hobby';
  remove.textContent = '\u00d7';
  remove.addEventListener('click', () => {
    row.remove();
    renumberHobbies(name);
    showFieldError(name, validate(readValues())[name]);
  });
  row.append(input, remove);
  rows.append(row);
  renumberHobbies(name);
  return input;
}

for (const name of Object.keys(hobbyFields)) {
  addHobby(name);
  fieldRoot(name).querySelector('.add-hobby').addEventListener('click', () => addHobby(name).focus());
}

// Converts the hobby textboxes into the single string fields the backend expects.
function readValues() {
  const values = Object.fromEntries(new FormData(form));
  for (const name of Object.keys(hobbyFields)) {
    values[name] = joinHobbies(hobbyInputs(name).map((input) => input.value));
  }
  return values;
}

form.addEventListener('input', (event) => {
  const name = event.target.closest('[data-field]')?.dataset.field ?? event.target.name;
  if (name in fields && fieldRoot(name).getAttribute('aria-invalid') === 'true') {
    showFieldError(name, validate(readValues())[name]);
  }
});

function updateButton() {
  const seconds = Math.ceil((blockedUntil - Date.now()) / 1000);
  button.disabled = submitting || seconds > 0;
  button.textContent = submitting ? 'Registering...' : seconds > 0 ? `Try again in ${seconds}s` :
    outcomeUnknown ? 'Submit again manually' : 'Register \u2192';
  if (seconds <= 0 && countdown) {
    clearInterval(countdown);
    countdown = undefined;
  }
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (submitting || Date.now() < blockedUntil) return;
  const values = readValues();
  const errors = validate(values);
  // Native email validity complements the shared syntax and length checks.
  if (values.email && form.elements.namedItem('email').validity.typeMismatch) {
    errors.email = 'Enter a valid email address.';
  }
  for (const name of Object.keys(fields)) showFieldError(name, errors[name]);
  if (Object.keys(errors).length) {
    showStatus('Please check the highlighted fields before continuing.');
    const firstError = fieldRoot(Object.keys(errors)[0]);
    (firstError.querySelector('input') ?? firstError).focus();
    return;
  }
  if (outcomeUnknown && !window.confirm(
    'The previous attempt may already have created a registration. Submitting again cannot recover its ID and may return a duplicate. Submit anyway?'
  )) return;

  submitting = true;
  fieldset.disabled = true;
  form.setAttribute('aria-busy', 'true');
  showStatus('Sending your registration to the local backend...');
  updateButton();
  // Consent is enforced in the browser only, so the backend request stays unchanged.
  const details = { ...values };
  delete details.consent;
  try {
    const response = await fetch('/api/v1/registrations', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ ...details, age: Number(values.age), scheme_months: Number(values.scheme_months) }),
      signal: AbortSignal.timeout(20_000),
    });
    const text = await response.text();
    let body;
    try {
      body = JSON.parse(text);
    } catch {
      // Missing or non-JSON bodies must still be classified by HTTP status.
      body = null;
    }
    const result = interpretResponse(response.status, body);
    if (result.id) {
      document.querySelector('#registration-id').textContent = result.id;
      form.reset();
      form.hidden = true;
      document.querySelector('#success').hidden = false;
      document.querySelector('#success').focus();
    } else {
      outcomeUnknown = outcomeUnknown || Boolean(result.unknown);
      let message = result.message;
      const requestId = response.headers.get('x-request-id') || body?.request_id;
      if (typeof requestId === 'string' && /^[a-f0-9-]{36}$/i.test(requestId)) {
        message += ` Support request ID: ${requestId}`;
      }
      if (response.status === 429) {
        blockedUntil = Date.now() + retryDelay(response.headers.get('retry-after'));
        countdown = setInterval(updateButton, 250);
      }
      showStatus(message);
    }
  } catch {
    outcomeUnknown = true;
    showStatus(unknownOutcome().message);
  } finally {
    submitting = false;
    fieldset.disabled = false;
    form.setAttribute('aria-busy', 'false');
    updateButton();
  }
});

document.querySelector('#copy-button').addEventListener('click', async () => {
  const copyStatus = document.querySelector('#copy-status');
  try {
    await navigator.clipboard.writeText(document.querySelector('#registration-id').textContent);
    copyStatus.textContent = 'ID copied. Keep it somewhere safe for activation.';
  } catch {
    copyStatus.textContent = 'Copy was not available. Select and save the ID above manually.';
  }
});

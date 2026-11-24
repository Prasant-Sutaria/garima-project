export const fields = {
  user_name: { label: 'User name', max: 120 },
  mobile_number: { label: 'Mobile number' },
  email: { label: 'Email', max: 254 },
  age: { label: 'Age' },
  gender: { label: 'Gender', max: 64 },
  indoor_activity: { label: 'Indoor activity', max: 2000 },
  outdoor_activity: { label: 'Outdoor activity', max: 2000 },
};

export function joinHobbies(hobbies) {
  return hobbies.map((hobby) => hobby.trim()).filter(Boolean).join(', ');
}

export function validate(values) {
  const errors = {};
  for (const [name, field] of Object.entries(fields)) {
    const value = values[name] ?? '';
    if (!value.trim()) errors[name] = `Enter ${field.label.toLowerCase()}.`;
    else if (field.max && value.length > field.max) {
      errors[name] = `Use no more than ${field.max.toLocaleString('en-US')} characters.`;
    }
  }
  if (values.mobile_number?.trim() && !/^\+[1-9][0-9]{1,14}$/.test(values.mobile_number)) {
    errors.mobile_number = 'Use international format, for example +14155550123.';
  }
  if (values.email?.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(values.email)) {
    errors.email = 'Enter a valid email address.';
  }
  if (values.age?.trim() && (!/^\d+$/.test(values.age) || Number(values.age) > 120)) {
    errors.age = 'Enter a whole number from 0 to 120.';
  }
  return errors;
}

export function retryDelay(value, now = Date.now()) {
  if (value && /^\d+$/.test(value.trim())) return Math.max(1000, Number(value) * 1000);
  const date = value ? Date.parse(value) : NaN;
  return Number.isFinite(date) ? Math.max(1000, date - now) : 60_000;
}

export function interpretResponse(status, body) {
  if (status === 201 && typeof body?.registration_id === 'string' &&
      /^P-[A-Z0-9]{6}$/.test(body.registration_id)) {
    return { id: body.registration_id };
  }
  const messages = {
    409: 'Registration could not be completed. A submitted contact may already be registered. No registration ID can be recovered here.',
    413: 'The request is too large. Shorten your entries and try again.',
    422: 'The backend rejected the details. Check all fields and their formats before submitting again.',
    429: 'Too many registration attempts. Please wait before trying again.',
  };
  if (messages[status]) return { message: messages[status] };
  if (status === 201 || status >= 500) return unknownOutcome();
  return { message: `Registration failed (HTTP ${status}). Check that the backend is running with the local profile enabled.` };
}

export function unknownOutcome() {
  return {
    unknown: true,
    message: 'The registration outcome is unknown. The backend may have created it, but no valid ID was received. There is no ID recovery endpoint. Nothing will be retried automatically; submitting again may result in a duplicate without returning your ID.',
  };
}

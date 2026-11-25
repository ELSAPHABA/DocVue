import { describe, expect, it } from 'vitest';
import { FormControl, Validators } from '@angular/forms';
describe('creator form bootstrap', () => {
  it('validates required title', () => {
    const title = new FormControl('', Validators.required);
    expect(title.valid).toBe(false);
    title.setValue('DockVue');
    expect(title.valid).toBe(true);
  });
});

import { ApiFailure } from './api-errors';
it('keeps invalid, missing, conflict, throttled and unavailable responses distinct',()=>{
  const messages=[400,401,403,404,409,429,503].map(status=>new ApiFailure(status).message);
  expect(new Set(messages).size).toBe(messages.length);
  expect(new ApiFailure(429).message).toContain('Wait');expect(new ApiFailure(503).message).toContain('temporarily unavailable');
});

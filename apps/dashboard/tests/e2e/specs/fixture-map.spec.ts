import {test,expect} from '../helpers/test';
for(const width of [1366,390])test(`fixture sample-link toggle retains map position at ${width}px`,async({page})=>{
  await page.setViewportSize({width,height:900});await page.goto('/dashboard');
  const toggle=page.getByRole('checkbox',{name:'Show sample links'});await toggle.scrollIntoViewIfNeeded();
  const position=()=>page.locator('.city-map').evaluate(map=>{const box=map.getBoundingClientRect();return {x:box.x+scrollX,y:box.y+scrollY,width:box.width,height:box.height};});
  const initial=await position();await toggle.uncheck();await expect(page.locator('.transport-link')).toHaveCount(0);expect(await position()).toEqual(initial);
  await toggle.check();await expect(page.locator('.transport-link')).toHaveCount(7);expect(await position()).toEqual(initial);
});

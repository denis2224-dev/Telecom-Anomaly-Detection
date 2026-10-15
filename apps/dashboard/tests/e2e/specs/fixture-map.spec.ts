import {test,expect} from '../helpers/test';
for(const width of [1366,390])test(`fixture map uses plain fixed targets at ${width}px`,async({page})=>{
  await page.setViewportSize({width,height:900});await page.goto('/dashboard');
  await expect(page.locator('.city-marker')).toHaveCount(10);
  await expect(page.locator('.map-link')).toHaveCount(12);
  await expect(page.locator('.link-marker')).toHaveCount(0);
  const position=()=>page.locator('.city-map').evaluate(map=>{const box=map.getBoundingClientRect();return {x:box.x+scrollX,y:box.y+scrollY,width:box.width,height:box.height};});
  const initial=await position();
  await page.getByRole('button',{name:'Inspect Chișinău – Hîncești',exact:true}).click();
  expect(await position()).toEqual(initial);
});

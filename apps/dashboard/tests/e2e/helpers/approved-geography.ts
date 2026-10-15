import geography from '../../../../../contracts/geography/trace-link-geography-v2.json';
// Controlled protected-response catalogue. Legacy geography contracts remain unchanged.
export default {
  catalogueVersion: 'controlled-approved-geography', topologyVersion: 'controlled-approved-topology',
  cities: geography.cities.map(city=>({cityId:city.contractCityId,displayName:city.displayName,
    scopes:{VOLTE:`VOLTE-MD-${city.contractCityId}`,SMS:`SMS-MD-${city.contractCityId}`}})),
};

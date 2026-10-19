import { ConfiguracionEmpresa } from '../../../core/models/empresa.models';

export type CentroMapa = Pick<
  ConfiguracionEmpresa,
  'nombre' | 'direccionCentral' | 'latitudCentral' | 'longitudCentral'
>;

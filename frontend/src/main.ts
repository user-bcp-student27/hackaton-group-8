import { bootstrapApplication } from '@angular/platform-browser';
import { App } from './app';

bootstrapApplication(App).catch((error: unknown) => {
  console.error('No se pudo iniciar Tilin', error);
  const message = document.createElement('p');
  message.textContent = 'No se pudo iniciar la wallet. Recarga la página o revisa el servidor.';
  document.body.replaceChildren(message);
});

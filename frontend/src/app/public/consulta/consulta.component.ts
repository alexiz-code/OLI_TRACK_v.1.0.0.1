import { Component, inject } from '@angular/core';
import {
  FormBuilder,
  ReactiveFormsModule,
  Validators
} from '@angular/forms';
import { HttpClient } from '@angular/common/http';

import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-consulta',
  standalone: true,
  imports: [
    ReactiveFormsModule
  ],
  templateUrl: './consulta.component.html',

  styleUrl: './consulta.component.css'
})
export class ConsultaComponent {

  private readonly fb = inject(FormBuilder);
  private readonly http = inject(HttpClient);

  form = this.fb.group({
    nombre: [
      '',
      Validators.required
    ],

    email: [
      '',
      [
        Validators.required,
        Validators.email
      ]
    ],

    asunto: [
      '',
      Validators.required
    ],

    mensaje: [
      '',
      [
        Validators.required,
        Validators.maxLength(2000)
      ]
    ]
  });

  enviando = false;
  enviado = false;

  enviar(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.enviando = true;

    this.http
      .post(
        `${environment.apiUrl}/consultas`,
        this.form.getRawValue()
      )
      .subscribe({
        next: () => {
          this.enviado = true;
          this.enviando = false;
        },

        error: (error) => {
          console.error(
            'Error al enviar la consulta:',
            error
          );

          this.enviando = false;
        }
      });
  }
}
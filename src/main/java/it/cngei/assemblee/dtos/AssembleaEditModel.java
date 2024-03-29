package it.cngei.assemblee.dtos;

import lombok.Data;

@Data
public class AssembleaEditModel {
  private String nome;
  private String descrizione;
  private String partecipanti;
  private Long totalePartecipanti;
  private String dateTime;
  private String odg;
  private boolean require2fa;
  private boolean nazionale;
  private boolean inPresenza;
}

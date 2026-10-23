package com.physicalchange.domain;

import jakarta.persistence.*;

/** Pregunta de un formulario. options: JSON con las opciones (SINGLE y MULTIPLE). */
@Entity
@Table(name = "LIBRARY_FORM_QUESTION")
public class LibraryFormQuestion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "FORM_ID", nullable = false) private Long formId;
    @Column(nullable = false) private Integer position;
    @Column(nullable = false, length = 12) private String kind;
    @Column(nullable = false, length = 300) private String label;
    @Column(nullable = false) private boolean required;
    @Column(nullable = false, length = 3000) private String options = "[]";

    public Long getId() { return id; }
    public Long getFormId() { return formId; }
    public void setFormId(Long f) { this.formId = f; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer p) { this.position = p; }
    public String getKind() { return kind; }
    public void setKind(String k) { this.kind = k; }
    public String getLabel() { return label; }
    public void setLabel(String l) { this.label = l; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean r) { this.required = r; }
    public String getOptions() { return options; }
    public void setOptions(String o) { this.options = o; }
}

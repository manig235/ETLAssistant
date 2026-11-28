import {createContext,useContext} from 'react';

export const NameContext=createContext('영민이');
export const useAssistantName=()=>useContext(NameContext);
